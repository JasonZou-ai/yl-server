package cn.yl.modules.evaluation.service;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.evaluation.domain.entity.EvalAnswer;
import cn.yl.modules.evaluation.domain.entity.EvalItem;
import cn.yl.modules.evaluation.domain.entity.EvalOrder;
import cn.yl.modules.evaluation.domain.entity.EvalReviewLog;
import cn.yl.modules.evaluation.domain.entity.EvalTask;
import cn.yl.modules.evaluation.domain.entity.GbRuleVersion;
import cn.yl.modules.evaluation.dto.AnswerInput;
import cn.yl.modules.evaluation.dto.AssessmentResultResponse;
import cn.yl.modules.evaluation.dto.CreateAssessmentRequest;
import cn.yl.modules.evaluation.dto.DimScoreItem;
import cn.yl.modules.evaluation.dto.EvalItemDto;
import cn.yl.modules.evaluation.dto.ItemBankResponse;
import cn.yl.modules.evaluation.dto.SubmitAnswersRequest;
import cn.yl.modules.evaluation.dto.SubmitAssessmentRequest;
import cn.yl.modules.evaluation.engine.Answer;
import cn.yl.modules.evaluation.engine.GradeResult;
import cn.yl.modules.evaluation.engine.RuleSet;
import cn.yl.modules.evaluation.engine.SpecialFlags;
import cn.yl.modules.evaluation.mapper.EvalAnswerMapper;
import cn.yl.modules.evaluation.mapper.EvalItemMapper;
import cn.yl.modules.evaluation.mapper.EvalOrderMapper;
import cn.yl.modules.evaluation.mapper.EvalReviewLogMapper;
import cn.yl.modules.evaluation.mapper.EvalTaskMapper;
import cn.yl.modules.evaluation.mapper.GbRuleVersionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评估中心服务（C1）：任务创建 → 26 项作答 → 调分级引擎 → 落等级结果。
 *
 * <p>这是引擎<b>第一次被真实业务调用</b>的落点：作答经 {@code eval_answer} 落库，提交时统一转为引擎入参， 判定结果回写 {@code eval_order}
 * 并写流转日志，形成可审计的业务闭环。
 *
 * <p>状态机：DRAFT →（提交并判定）→ SUBMITTED；被驳回后回到 REJECTED（可修改重提）。 复核/发布/作废见 {@link
 * AssessmentReviewService}（PRD §4.4）。
 *
 * <p>阈值与等级区间的唯一来源是 RuleSet JSON，本服务只做码值翻译（{@link GradeCodeTranslator}）与持久化，不复制任何国标常量。
 */
@Service
@RequiredArgsConstructor
public class AssessmentService {

    private static final String STATUS_DRAFT = "DRAFT";
    private static final String STATUS_SUBMITTED = "SUBMITTED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String ACTION_SUBMIT = "SUBMIT";
    private static final String STANDARD_CODE = "GB/T 42195-2022";
    private static final int TASK_DONE = 2;

    private final EvalTaskMapper taskMapper;
    private final EvalOrderMapper orderMapper;
    private final EvalAnswerMapper answerMapper;
    private final EvalReviewLogMapper logMapper;
    private final EvalItemMapper itemMapper;
    private final GbRuleVersionMapper ruleVersionMapper;
    private final AssessmentGradingService gradingService;

    /**
     * 新建评估：创建评估任务 + 评估单（DRAFT）。
     *
     * @param req 新建请求
     * @return 评估单当前状态（未判定）
     */
    public AssessmentResultResponse create(CreateAssessmentRequest req) {
        if (req.getElderId() == null || req.getOrgId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "elderId 与 orgId 必填");
        }
        EvalTask task = buildTask(req);
        taskMapper.insert(task);

        EvalOrder order = buildOrder(req, task.getId());
        orderMapper.insert(order);
        return toResponse(order, null);
    }

    /**
     * 保存作答（逐项 upsert，支持离线补答与重复保存）。
     *
     * @param orderId 评估单 id
     * @param req 作答列表
     * @return 本次保存的作答条数
     */
    public int saveAnswers(Long orderId, SubmitAnswersRequest req) {
        EvalOrder order = requireOrder(orderId);
        requireEditable(order);
        List<AnswerInput> inputs = req.getAnswers();
        if (inputs.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "作答列表为空");
        }
        for (AnswerInput input : inputs) {
            upsertAnswer(orderId, input);
        }
        return inputs.size();
    }

    /**
     * 提交评估：校验必答完整性 → 调分级引擎 → 回写等级结果 → 写流转日志。
     *
     * <p><b>昏迷例外</b>：GB/T 42195-2022 表6 说明1「昏迷」为**直判**路径（无需逐项录入即判能力完全丧失）， 故 {@code coma=true} 时豁免
     * ER-06 必答完整性校验；否则会出现「昏迷老人被要求填满 26 项才能提交」的合规性矛盾。 非昏迷路径一律强校验，不得静默放行。
     *
     * @param orderId 评估单 id
     * @param req 提交请求（特殊标志 + 变更原因）
     * @return 判定结果（含初判/终判等级与变化原因）
     */
    @Transactional(rollbackFor = Exception.class)
    public AssessmentResultResponse submit(Long orderId, SubmitAssessmentRequest req) {
        EvalOrder order = requireOrder(orderId);
        requireEditable(order);
        List<EvalAnswer> answers =
                answerMapper.selectList(new QueryWrapper<EvalAnswer>().eq("order_id", orderId));
        if (!req.isComa()) {
            validateRequiredAnswered(answers);
        }

        String fromStatus = order.getStatus();
        GradeResult result = gradingService.grade(toEngineAnswers(answers), toFlags(req));
        applyGradeResult(order, result, answers, req);
        orderMapper.updateById(order);
        writeLog(order, req, ACTION_SUBMIT, fromStatus, STATUS_SUBMITTED);
        completeTask(order.getTaskId());
        return toResponse(order, result);
    }

    /** 查询评估单当前状态（提交前无判定结果，等级字段为空）。 */
    public AssessmentResultResponse get(Long orderId) {
        return toResponse(requireOrder(orderId), null);
    }

    private EvalTask buildTask(CreateAssessmentRequest req) {
        EvalTask task = new EvalTask();
        task.setTaskNo("ET" + UUID.randomUUID().toString().substring(0, 12));
        task.setElderId(req.getElderId());
        task.setOrgId(req.getOrgId());
        task.setAssessorId(req.getAssessorId());
        task.setScene(req.getScene());
        Integer priority = req.getPriority();
        task.setPriority(priority == null ? Integer.valueOf(0) : priority);
        task.setStatus(req.getAssessorId() == null ? 0 : 1);
        task.setDeleted(0);
        task.setCreatedBy(req.getAssessorId());
        return task;
    }

    private EvalOrder buildOrder(CreateAssessmentRequest req, Long taskId) {
        EvalOrder order = new EvalOrder();
        order.setOrderNo("EO" + UUID.randomUUID().toString().substring(0, 12));
        order.setTaskId(taskId);
        order.setElderId(req.getElderId());
        order.setOrgId(req.getOrgId());
        order.setAssessorId(req.getAssessorId());
        // eval_order.rule_version_id 为 NOT NULL 且无默认值：创建即绑定当前生效规则版本，
        // 使评估单从草稿期就持有事后复算锚点（避免提交前 INSERT 触发 NOT NULL 违例）。
        order.setRuleVersionId(resolveRuleVersionId(gradingService.meta().getRuleVersion()));
        order.setStatus(STATUS_DRAFT);
        order.setGradeUpgraded(0);
        order.setRefuseCount(0);
        order.setRowVersion(0);
        order.setDeleted(0);
        order.setChangeReason(req.getChangeReason());
        return order;
    }

    private void upsertAnswer(Long orderId, AnswerInput input) {
        if (input.getItemId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "作答缺少 itemId");
        }
        EvalAnswer existing =
                answerMapper.selectOne(
                        new QueryWrapper<EvalAnswer>()
                                .eq("order_id", orderId)
                                .eq("item_id", input.getItemId()));
        EvalAnswer answer = existing == null ? new EvalAnswer() : existing;
        applyInput(answer, orderId, input);
        if (existing == null) {
            answerMapper.insert(answer);
        } else {
            answerMapper.updateById(answer);
        }
    }

    private void applyInput(EvalAnswer answer, Long orderId, AnswerInput input) {
        boolean refused = Boolean.TRUE.equals(input.getRefused());
        int state = input.getAnswerState() == null ? (refused ? 3 : 1) : input.getAnswerState();
        answer.setOrderId(orderId);
        answer.setItemId(input.getItemId());
        answer.setDimensionCode(GradeCodeTranslator.toDimensionCode(input.getItemId()));
        answer.setOptionCode(input.getOptionCode());
        answer.setScore(refused || input.getScore() == null ? null : input.getScore());
        answer.setIsRefused(refused ? 1 : 0);
        answer.setAnswerState(state);
        answer.setHasEvidence(Boolean.TRUE.equals(input.getHasEvidence()) ? 1 : 0);
        answer.setCostMs(input.getCostMs());
        answer.setAnsweredAt(LocalDateTime.now());
        answer.setRowVersion(0);
    }

    /** ER-06：必答项应填未填即阻断提交。 */
    private void validateRequiredAnswered(List<EvalAnswer> answers) {
        if (answers == null || answers.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "尚未录入任何作答，无法提交");
        }
        List<EvalItem> required =
                itemMapper.selectList(
                        new QueryWrapper<EvalItem>().eq("is_required", 1).eq("deleted", 0));
        Set<Long> answered = new HashSet<>();
        for (EvalAnswer a : answers) {
            answered.add(a.getItemId());
        }
        List<String> missing = new ArrayList<>();
        for (EvalItem item : required) {
            if (!answered.contains(item.getId())) {
                missing.add(item.getItemCode());
            }
        }
        if (!missing.isEmpty()) {
            throw new BizException(
                    ErrorCode.PARAM_INVALID, "必答项未填写（ER-06）：" + String.join(",", missing));
        }
    }

    private List<Answer> toEngineAnswers(List<EvalAnswer> answers) {
        List<Answer> out = new ArrayList<>();
        for (EvalAnswer a : answers) {
            int score = a.getScore() == null ? 0 : a.getScore().intValue();
            out.add(new Answer(a.getItemId().intValue(), score));
        }
        return out;
    }

    private SpecialFlags toFlags(SubmitAssessmentRequest req) {
        SpecialFlags flags = new SpecialFlags();
        flags.setComa(req.isComa());
        flags.setDementia(req.isDementia());
        flags.setMentalDisorder(req.isMentalDisorder());
        flags.setRiskEventCount(req.getRiskEventCount());
        return flags;
    }

    private void applyGradeResult(
            EvalOrder order,
            GradeResult result,
            List<EvalAnswer> answers,
            SubmitAssessmentRequest req) {
        order.setRuleVersionId(resolveRuleVersionId(result.getRuleVersion()));
        order.setTotalScore(BigDecimal.valueOf(result.getTotalScore()));
        order.setGradeCode(GradeCodeTranslator.toGradeCode(result.getLevelCode()));
        order.setGradeUpgraded(result.getSeverityChange() > 0 ? 1 : 0);
        order.setUpgradeReason(result.getLevelChangeReason());
        // PRD §1.4：初判 + 终判 + 变化原因；C3：规则指纹（事后复算锚点）
        order.setPreliminaryGradeCode(result.getPreliminaryLevelCode());
        order.setPreliminaryLevelName(result.getPreliminaryName());
        order.setFinalLevelCode(result.getLevelCode());
        order.setFinalLevelName(result.getLevelName());
        order.setGradeChangeReason(result.getLevelChangeReason());
        order.setRuleSha(result.getRuleSha());
        order.setRefuseCount(countRefused(answers));
        order.setStatus(STATUS_SUBMITTED);
        order.setSubmitAt(LocalDateTime.now());
        if (req.getChangeReason() != null) {
            order.setChangeReason(req.getChangeReason());
        }
    }

    private int countRefused(List<EvalAnswer> answers) {
        int refused = 0;
        for (EvalAnswer a : answers) {
            if (a.getIsRefused() != null && a.getIsRefused() == 1) {
                refused++;
            }
        }
        return refused;
    }

    /** 绑定判定所用规则版本；版本未登记时按权威规则集自动登记（保证事后复算可追溯）。 */
    private Long resolveRuleVersionId(String ruleVersion) {
        GbRuleVersion existing =
                ruleVersionMapper.selectOne(
                        new QueryWrapper<GbRuleVersion>()
                                .eq("standard_code", STANDARD_CODE)
                                .eq("version", ruleVersion));
        if (existing != null) {
            return existing.getId();
        }
        GbRuleVersion created = new GbRuleVersion();
        created.setStandardCode(STANDARD_CODE);
        created.setVersion(ruleVersion);
        created.setStatus(1);
        created.setEffectiveAt(LocalDateTime.now());
        created.setRemark("引擎权威规则集自动登记：ruleVersion=" + ruleVersion);
        ruleVersionMapper.insert(created);
        return created.getId();
    }

    private void writeLog(
            EvalOrder order,
            SubmitAssessmentRequest req,
            String action,
            String fromStatus,
            String toStatus) {
        EvalReviewLog log = new EvalReviewLog();
        log.setOrderId(order.getId());
        log.setAction(action);
        log.setFromStatus(fromStatus);
        log.setToStatus(toStatus);
        log.setOperatorId(
                req.getOperatorId() == null ? order.getAssessorId() : req.getOperatorId());
        log.setOpinion(req.getChangeReason());
        log.setCreatedAt(LocalDateTime.now());
        logMapper.insert(log);
    }

    private void completeTask(Long taskId) {
        if (taskId == null) {
            return;
        }
        EvalTask task = taskMapper.selectById(taskId);
        if (task == null) {
            return;
        }
        task.setStatus(TASK_DONE);
        taskMapper.updateById(task);
    }

    private EvalOrder requireOrder(Long orderId) {
        EvalOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "评估单不存在: " + orderId);
        }
        return order;
    }

    /**
     * 可编辑校验：草稿（DRAFT）或被驳回（REJECTED）可继续作答/重提。
     *
     * <p>PRD §4.4：已驳回的评估单「可修改重提」——驳回后回到可编辑态，由录入人按复核意见修改后再次提交。
     */
    private void requireEditable(EvalOrder order) {
        if (!STATUS_DRAFT.equals(order.getStatus()) && !STATUS_REJECTED.equals(order.getStatus())) {
            throw new BizException(
                    ErrorCode.CONFLICT, "仅 DRAFT / REJECTED 状态可作答或提交，当前: " + order.getStatus());
        }
    }

    private AssessmentResultResponse toResponse(EvalOrder order, GradeResult result) {
        AssessmentResultResponse resp = new AssessmentResultResponse();
        resp.setOrderId(order.getId());
        resp.setOrderNo(order.getOrderNo());
        resp.setTaskId(order.getTaskId());
        resp.setStatus(order.getStatus());
        resp.setTotalScore(order.getTotalScore());
        resp.setGradeCode(order.getGradeCode());
        resp.setGradeUpgraded(order.getGradeUpgraded());
        resp.setRefuseCount(order.getRefuseCount());
        if (result != null) {
            resp.setPreliminaryLevelCode(result.getPreliminaryLevelCode());
            resp.setPreliminaryLevelName(result.getPreliminaryName());
            resp.setLevelCode(result.getLevelCode());
            resp.setLevelName(result.getLevelName());
            resp.setLevelChangeReason(result.getLevelChangeReason());
            resp.setRuleVersion(result.getRuleVersion());
            resp.setRuleSha(result.getRuleSha());
            resp.setDimScores(buildDimScores(result));
        }
        return resp;
    }

    /**
     * 题库下发：26 项二级指标元信息 + 所属规则版本指纹。
     *
     * <p>{@code itemId} 即引擎指标编号（101~405），与 {@code eval_item.id} 同源，因此四端提交作答时 可直接携带该 id，无需在端上维护「本地题号
     * → 指标 id」的硬编码映射（解耦口径变更）。
     *
     * @return 题库元信息（不含选项文本与注释语料，该部分由端上维护）
     */
    public ItemBankResponse items() {
        List<EvalItem> rows =
                itemMapper.selectList(
                        new QueryWrapper<EvalItem>().eq("deleted", 0).orderByAsc("order_no", "id"));
        List<EvalItemDto> dtos = new ArrayList<>();
        for (EvalItem row : rows) {
            dtos.add(
                    new EvalItemDto(
                            row.getId(),
                            row.getDimensionCode(),
                            row.getItemName(),
                            row.getScoreMax() == null ? 0 : row.getScoreMax().intValue(),
                            row.getOrderNo(),
                            row.getIsRequired()));
        }
        ItemBankResponse resp = new ItemBankResponse();
        resp.setRuleVersion(gradingService.meta().getRuleVersion());
        resp.setRuleSha(gradingService.meta().getRuleSha());
        resp.setItems(dtos);
        return resp;
    }

    /** 维度得分明细：分值取自引擎结果，满分取自规则集维度定义（四端不本地算分）。 */
    private List<DimScoreItem> buildDimScores(GradeResult result) {
        List<DimScoreItem> items = new ArrayList<>();
        for (RuleSet.Dimension d : gradingService.dimensions()) {
            int score = result.getDimScores().getOrDefault(d.getCode(), 0);
            items.add(new DimScoreItem(d.getCode(), d.getName(), score, d.getFullScore()));
        }
        return items;
    }
}
