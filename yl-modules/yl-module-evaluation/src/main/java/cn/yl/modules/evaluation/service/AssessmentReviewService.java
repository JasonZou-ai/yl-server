package cn.yl.modules.evaluation.service;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.evaluation.domain.RetentionPolicy;
import cn.yl.modules.evaluation.domain.entity.EvalOrder;
import cn.yl.modules.evaluation.domain.entity.EvalReviewLog;
import cn.yl.modules.evaluation.domain.entity.GbRuleVersion;
import cn.yl.modules.evaluation.dto.AssessmentResultResponse;
import cn.yl.modules.evaluation.dto.ReviewLogResponse;
import cn.yl.modules.evaluation.mapper.EvalOrderMapper;
import cn.yl.modules.evaluation.mapper.EvalReviewLogMapper;
import cn.yl.modules.evaluation.mapper.GbRuleVersionMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评估单复核状态机（PRD §4.4）：SUBMITTED → PUBLISHED / REJECTED → VOID。
 *
 * <p>三条硬约束（缺一即拒绝）：
 *
 * <ol>
 *   <li><b>录入与复核互斥</b>（PRD §2.2）：{@code operatorId} 不得等于 {@code eval_order.assessor_id}；
 *   <li><b>驳回必须留意见</b>（PRD §4.3）：退回录入人须保留意见，否则无法据以修改重提；
 *   <li><b>发布必须有报告</b>（流程 A ⑤⑥）：复核通过的对象是报告，报告未生成不得发布。
 * </ol>
 *
 * <p><b>操作者来源（CR-M2-002）</b>：三个流转方法的 {@code operatorId} 均为<b>显式入参</b>，由 Controller 从登录态取得 （{@code
 * SecurityUtils.requireUserId()}）。本服务不接收任何"客户端可写"的身份字段，故"请求体传他人 ID 即可冒用操作者"的缺口在
 * <b>编译期</b>即无法复现。授权判定由第二闸 {@code @RequiresPermission} 在 Controller 前置完成。
 *
 * <p>每次流转写 {@code eval_review_log}，构成可追溯审计链；报告状态随之联动（发布→有效，作废→已作废， 驳回→报告作废以便重提后重新生成）。
 */
@Service
@RequiredArgsConstructor
public class AssessmentReviewService {

    private static final String STATUS_SUBMITTED = "SUBMITTED";
    private static final String STATUS_PUBLISHED = "PUBLISHED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String STATUS_VOID = "VOID";
    private static final String STATUS_DRAFT = "DRAFT";

    private static final String ACTION_PUBLISH = "PUBLISH";
    private static final String ACTION_REJECT = "REJECT";
    private static final String ACTION_VOID = "VOID";

    private final EvalOrderMapper orderMapper;
    private final EvalReviewLogMapper logMapper;
    private final GbRuleVersionMapper ruleVersionMapper;
    private final AssessmentReportService reportService;

    /**
     * 复核通过并发布：SUBMITTED → PUBLISHED，报告转「有效」。
     *
     * <p><b>操作者来源（CR-M2-002）</b>：{@code operatorId} 由调用方（Controller）从**登录态**取得后显式传入，本服务不再接收任何
     * "客户端可写"的请求体对象作为身份来源——此前取自请求体导致"持合法令牌即可冒用他人身份发布/作废他人评估单"。
     *
     * @param orderId 评估单 id
     * @param operatorId 复核人（登录态用户 ID，非请求体）
     * @param opinion 复核意见（发布可选）
     * @return 流转后的评估单状态
     */
    @Transactional(rollbackFor = Exception.class)
    public AssessmentResultResponse publish(Long orderId, long operatorId, String opinion) {
        EvalOrder order = requireReviewable(orderId);
        requireNotAssessor(order, operatorId);
        if (reportService.findReport(orderId) == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "报告尚未生成，无法发布: orderId=" + orderId);
        }
        LocalDateTime now = LocalDateTime.now();
        order.setStatus(STATUS_PUBLISHED);
        order.setReviewerId(operatorId);
        order.setReviewAt(now);
        order.setReviewOpinion(opinion);
        order.setPublishedAt(now);
        // 保留期起点取发布时点（ER-03：评估记录 5 年），到期由保留期链路转 ARCHIVED 封存；不写则永不进保留周期
        order.setRetainUntil(now.plusYears(RetentionPolicy.RECORD_RETENTION_YEARS));
        orderMapper.updateById(order);
        reportService.markPublished(orderId, now);
        writeLog(order, ACTION_PUBLISH, STATUS_SUBMITTED, STATUS_PUBLISHED, operatorId, opinion);
        return toResponse(order);
    }

    /**
     * 复核驳回：SUBMITTED → REJECTED，退回录入人可修改重提（PRD §4.4）。
     *
     * @param orderId 评估单 id
     * @param operatorId 复核人（登录态用户 ID）
     * @param opinion 复核意见（必填）
     * @return 流转后的评估单状态
     */
    @Transactional(rollbackFor = Exception.class)
    public AssessmentResultResponse reject(Long orderId, long operatorId, String opinion) {
        EvalOrder order = requireReviewable(orderId);
        requireNotAssessor(order, operatorId);
        if (opinion == null || opinion.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "驳回必须填写复核意见（PRD §4.3）");
        }
        LocalDateTime now = LocalDateTime.now();
        order.setStatus(STATUS_REJECTED);
        order.setReviewerId(operatorId);
        order.setReviewAt(now);
        order.setReviewOpinion(opinion);
        orderMapper.updateById(order);
        // 驳回即作废已生成报告：重提后判定结果可能变化，须重新生成，避免旧报告被误发布
        reportService.discardReport(orderId);
        writeLog(order, ACTION_REJECT, STATUS_SUBMITTED, STATUS_REJECTED, operatorId, opinion);
        return toResponse(order);
    }

    /**
     * 作废：SUBMITTED / PUBLISHED / REJECTED → VOID，报告转「已作废」（扫码验真提示失效）。
     *
     * <p>敏感操作（{@code evaluation:order:void}，{@code need_second_verify=1}）：端点须经第二闸权限码 + 第四闸二次验证留痕。
     *
     * @param orderId 评估单 id
     * @param operatorId 操作者（登录态用户 ID）
     * @param opinion 作废意见（必填，敏感操作留痕）
     * @return 流转后的评估单状态
     */
    @Transactional(rollbackFor = Exception.class)
    public AssessmentResultResponse voidOrder(Long orderId, long operatorId, String opinion) {
        EvalOrder order = requireOrder(orderId);
        if (STATUS_VOID.equals(order.getStatus())) {
            throw new BizException(ErrorCode.CONFLICT, "评估单已作废，不可重复作废");
        }
        if (STATUS_DRAFT.equals(order.getStatus())) {
            throw new BizException(
                    ErrorCode.EVAL_STATE_ILLEGAL, "草稿态评估单无需作废: " + order.getStatus());
        }
        requireNotAssessor(order, operatorId);
        if (opinion == null || opinion.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "作废必须填写意见（敏感操作留痕）");
        }
        String from = order.getStatus();
        order.setStatus(STATUS_VOID);
        order.setReviewerId(operatorId);
        order.setReviewAt(LocalDateTime.now());
        order.setReviewOpinion(opinion);
        orderMapper.updateById(order);
        reportService.markVoid(orderId);
        writeLog(order, ACTION_VOID, from, STATUS_VOID, operatorId, opinion);
        return toResponse(order);
    }

    /** 流转日志（审计链），按时间正序。 */
    public List<ReviewLogResponse> logs(Long orderId) {
        List<EvalReviewLog> rows =
                logMapper.selectList(
                        new QueryWrapper<EvalReviewLog>()
                                .eq("order_id", orderId)
                                .orderByAsc("created_at", "id"));
        List<ReviewLogResponse> out = new ArrayList<>();
        for (EvalReviewLog row : rows) {
            ReviewLogResponse resp = new ReviewLogResponse();
            resp.setId(row.getId());
            resp.setOrderId(row.getOrderId());
            resp.setAction(row.getAction());
            resp.setFromStatus(row.getFromStatus());
            resp.setToStatus(row.getToStatus());
            resp.setOperatorId(row.getOperatorId());
            resp.setOpinion(row.getOpinion());
            resp.setCreatedAt(row.getCreatedAt());
            out.add(resp);
        }
        return out;
    }

    private EvalOrder requireOrder(Long orderId) {
        EvalOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "评估单不存在: " + orderId);
        }
        return order;
    }

    private EvalOrder requireReviewable(Long orderId) {
        EvalOrder order = requireOrder(orderId);
        if (!STATUS_SUBMITTED.equals(order.getStatus())) {
            throw new BizException(
                    ErrorCode.EVAL_STATE_ILLEGAL, "仅 SUBMITTED（待复核）状态可复核，当前: " + order.getStatus());
        }
        return order;
    }

    /**
     * PRD §2.2：录入与复核人必须不同（互斥）。
     *
     * <p>{@code operatorId} 为登录态用户 ID（非请求体），故本校验拦的是"同一登录账号既录入又复核"，不能被伪造的请求体绕过。
     */
    private void requireNotAssessor(EvalOrder order, long operatorId) {
        if (order.getAssessorId() != null && order.getAssessorId().longValue() == operatorId) {
            throw new BizException(
                    ErrorCode.ROLE_NOT_PERMITTED, "录入人与复核人必须不同（互斥）：assessorId=" + operatorId);
        }
    }

    private void writeLog(
            EvalOrder order,
            String action,
            String from,
            String to,
            long operatorId,
            String opinion) {
        EvalReviewLog log = new EvalReviewLog();
        log.setOrderId(order.getId());
        log.setAction(action);
        log.setFromStatus(from);
        log.setToStatus(to);
        log.setOperatorId(operatorId);
        log.setOpinion(opinion);
        log.setCreatedAt(LocalDateTime.now());
        logMapper.insert(log);
    }

    private AssessmentResultResponse toResponse(EvalOrder order) {
        AssessmentResultResponse resp = new AssessmentResultResponse();
        resp.setOrderId(order.getId());
        resp.setOrderNo(order.getOrderNo());
        resp.setTaskId(order.getTaskId());
        resp.setStatus(order.getStatus());
        resp.setTotalScore(order.getTotalScore());
        resp.setGradeCode(order.getGradeCode());
        resp.setGradeUpgraded(order.getGradeUpgraded());
        resp.setRefuseCount(order.getRefuseCount());
        resp.setPreliminaryLevelCode(order.getPreliminaryGradeCode());
        resp.setPreliminaryLevelName(order.getPreliminaryLevelName());
        resp.setLevelCode(order.getFinalLevelCode());
        resp.setLevelName(order.getFinalLevelName());
        resp.setLevelChangeReason(order.getGradeChangeReason());
        resp.setRuleVersion(resolveVersion(order.getRuleVersionId()));
        resp.setRuleSha(order.getRuleSha());
        return resp;
    }

    private String resolveVersion(Long ruleVersionId) {
        if (ruleVersionId == null) {
            return null;
        }
        GbRuleVersion v = ruleVersionMapper.selectById(ruleVersionId);
        return v == null ? null : v.getVersion();
    }
}
