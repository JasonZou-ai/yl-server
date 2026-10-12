package cn.yl.modules.evaluation.service;

import cn.yl.api.security.LoginUser;
import cn.yl.api.security.SecurityUtils;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.evaluation.domain.RetentionPolicy;
import cn.yl.modules.evaluation.domain.entity.EvalAnswer;
import cn.yl.modules.evaluation.domain.entity.EvalItem;
import cn.yl.modules.evaluation.domain.entity.EvalOrder;
import cn.yl.modules.evaluation.domain.entity.EvalReport;
import cn.yl.modules.evaluation.domain.entity.GbRuleVersion;
import cn.yl.modules.evaluation.domain.entity.ReportDimensionScore;
import cn.yl.modules.evaluation.dto.DimScoreItem;
import cn.yl.modules.evaluation.dto.EvalReportResponse;
import cn.yl.modules.evaluation.engine.RuleSet;
import cn.yl.modules.evaluation.mapper.EvalAnswerMapper;
import cn.yl.modules.evaluation.mapper.EvalItemMapper;
import cn.yl.modules.evaluation.mapper.EvalOrderMapper;
import cn.yl.modules.evaluation.mapper.EvalReportMapper;
import cn.yl.modules.evaluation.mapper.GbRuleVersionMapper;
import cn.yl.modules.evaluation.mapper.ReportDimensionScoreMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 评估报告服务（C3）：把判定结果绑定到报告，满足 PRD §1.4 验收线。
 *
 * <p>报告须同时展示<b>初判等级 + 终判等级 + 等级变化原因</b>，并绑定判定所用规则版本（{@code ruleVersionId}）与规则集内容指纹 （{@code
 * ruleSha}）——两者共同构成<b>事后复算</b>的锚点：用同一版本的规则集重算应得到完全一致的结果。
 *
 * <p>{@code verifyHash} 为报告内容摘要（SHA-256），用于二维码验真与防篡改校验。
 *
 * <p>报告生成时同时落 {@code report_dimension_score}（4 维度得分 + 拒答数），作为 P02 雷达图数据源； 分值一律由服务端按 {@code
 * eval_answer} 聚合得到，四端不做本地算分（PRD 红线）。
 */
@Service
@RequiredArgsConstructor
public class AssessmentReportService {

    private static final String STATUS_SUBMITTED = "SUBMITTED";

    /** 报告生成时为「生成中」（草稿态，PRD P02：未发布不展示等级卡片）。 */
    private static final int REPORT_GENERATING = 0;

    private static final int REPORT_VALID = 1;
    private static final int REPORT_VOID = 2;

    /** 报告保留期（年），ER-03：评估记录与报告保留 5 年。 */
    private static final int RETAIN_YEARS = 5;

    private final EvalOrderMapper orderMapper;
    private final EvalReportMapper reportMapper;
    private final GbRuleVersionMapper ruleVersionMapper;
    private final EvalAnswerMapper answerMapper;
    private final EvalItemMapper itemMapper;
    private final ReportDimensionScoreMapper dimScoreMapper;
    private final AssessmentGradingService gradingService;

    /**
     * 生成（或返回已生成的）评估报告。幂等：一个评估单同时只允许一份有效报告（uk_order + active_uk）。
     *
     * @param orderId 评估单 id
     * @return 报告视图（含初判/终判/变化原因/规则版本/维度得分）
     */
    @Transactional(rollbackFor = Exception.class)
    public EvalReportResponse bindReport(Long orderId) {
        EvalOrder order = requireSubmittedOrder(orderId);
        EvalReport existing = findReport(orderId);
        if (existing != null) {
            return toResponse(existing, order);
        }
        EvalReport report = buildReport(order);
        reportMapper.insert(report);
        persistDimensionScores(report.getId(), buildDimScores(orderId));
        return toResponse(report, order);
    }

    /** 查询评估单的报告；未生成时抛 NOT_FOUND。 */
    public EvalReportResponse getReport(Long orderId) {
        EvalReport report = findReport(orderId);
        if (report == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "报告尚未生成: orderId=" + orderId);
        }
        assertPublishedToSelf(report);
        EvalOrder order = orderMapper.selectById(orderId);
        return toResponse(report, order);
    }

    /**
     * D4-R7 状态闸（开卡 §2.3）：<b>老人视角只读 status=1（已发布/有效）</b>。
     *
     * <p>为什么落在服务层：状态是<b>业务语义</b>，不是数据域语义——第三闸只负责「这行是不是我的」，无权表达 「已作废的不给看」。故本卡 §2.3 决议：锚（数据域）+
     * 状态闸（服务层）双保险，各司其职。
     *
     * <p>只对<b>本人域（data_scope=1）</b>生效：评估员/机构管理员在复核链路中<b>必须</b>能看到 {@code status=0}（生成中）与 {@code
     * status=2}（已作废）的报告，否则复核与作废流程无从操作—— 收紧老人视角时不能顺带把员工视角一起收紧（那是另一种欠权）。
     *
     * <p>错误码取 {@link ErrorCode#NOT_FOUND}（10004）而非 {@link ErrorCode#FORBIDDEN}：与 「他人
     * reportNo」同码，避免用错误码差异枚举出「存在但未发布」这一事实（开卡 §4.2·M2 同码口径）。
     *
     * <p>由 {@code getReport} 与 {@code ReportPdfService#requireVisible} 共用（报告详情与 PDF 下载两处读面
     * 必须同口径，否则「详情看不到、PDF 能下载」即为绕过）。
     */
    public void assertPublishedToSelf(EvalReport report) {
        LoginUser user = SecurityUtils.currentUserOrNull();
        if (user == null || !user.isSelfScope() || report.getStatus() == null) {
            return;
        }
        if (report.getStatus() != REPORT_VALID) {
            throw new BizException(
                    ErrorCode.NOT_FOUND, "报告未发布或已作废: orderId=" + report.getOrderId());
        }
    }

    /** 复核发布：报告转「有效」并记录发布时间（PRD P02：仅已发布展示等级卡片）。 */
    public void markPublished(Long orderId, LocalDateTime publishedAt) {
        EvalReport report = requireReport(orderId);
        report.setStatus(REPORT_VALID);
        report.setPublishedAt(publishedAt);
        // 报告保留期与评估单同口径（ER-03：报告 5 年），保证「单封存 + 报告封存」时点一致
        report.setRetainUntil(publishedAt.plusYears(RetentionPolicy.RECORD_RETENTION_YEARS));
        reportMapper.updateById(report);
    }

    /** 作废：报告转「已作废」，扫码验真须提示失效（PRD P02）。 */
    public void markVoid(Long orderId) {
        EvalReport report = findReport(orderId);
        if (report == null) {
            return;
        }
        report.setStatus(REPORT_VOID);
        reportMapper.updateById(report);
    }

    /**
     * 驳回后作废报告：重提会重新判定并重新生成报告，旧报告必须失效，防止被误发布。
     *
     * <p>采用软删（{@code deleted}=1）：{@code uk_order(order_id, active_uk)} 只约束活跃行，重提后允许再插一份新报告。
     */
    public void discardReport(Long orderId) {
        EvalReport report = findReport(orderId);
        if (report == null) {
            return;
        }
        report.setDeleted(1);
        report.setStatus(REPORT_VOID);
        reportMapper.updateById(report);
    }

    /** 内部：按评估单取报告实体（供复核/作废等状态流转复用）。 */
    public EvalReport findReport(Long orderId) {
        return reportMapper.selectOne(new QueryWrapper<EvalReport>().eq("order_id", orderId));
    }

    private EvalReport requireReport(Long orderId) {
        EvalReport report = findReport(orderId);
        if (report == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "报告尚未生成，无法发布: orderId=" + orderId);
        }
        return report;
    }

    private EvalOrder requireSubmittedOrder(Long orderId) {
        EvalOrder order = orderMapper.selectById(orderId);
        if (order == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "评估单不存在: " + orderId);
        }
        if (!STATUS_SUBMITTED.equals(order.getStatus())) {
            throw new BizException(
                    ErrorCode.CONFLICT, "仅 SUBMITTED 状态可生成报告，当前: " + order.getStatus());
        }
        return order;
    }

    private EvalReport buildReport(EvalOrder order) {
        String content = canonicalContent(order);
        EvalReport report = new EvalReport();
        report.setReportNo("RP" + UUID.randomUUID().toString().substring(0, 12));
        report.setOrderId(order.getId());
        report.setElderId(order.getElderId());
        report.setOrgId(order.getOrgId());
        report.setGradeCode(order.getGradeCode());
        report.setTotalScore(order.getTotalScore());
        report.setRuleVersionId(order.getRuleVersionId());
        report.setRuleSha(order.getRuleSha());
        report.setPreliminaryGradeCode(order.getPreliminaryGradeCode());
        report.setPreliminaryLevelName(order.getPreliminaryLevelName());
        report.setFinalLevelCode(order.getFinalLevelCode());
        report.setFinalLevelName(order.getFinalLevelName());
        report.setLevelChangeReason(order.getGradeChangeReason());
        report.setVerifyHash(sha256(content));
        report.setStatus(REPORT_GENERATING);
        report.setGeneratedAt(LocalDateTime.now());
        report.setRetainUntil(LocalDateTime.now().plusYears(RETAIN_YEARS));
        report.setDeleted(0);
        return report;
    }

    /** 报告内容摘要输入串：判定三要素 + 规则锚点，任一变化都会导致摘要变化（防篡改）。 */
    private String canonicalContent(EvalOrder order) {
        return String.join(
                "|",
                String.valueOf(order.getOrderNo()),
                String.valueOf(order.getTotalScore()),
                String.valueOf(order.getGradeCode()),
                String.valueOf(order.getPreliminaryGradeCode()),
                String.valueOf(order.getFinalLevelCode()),
                String.valueOf(order.getGradeChangeReason()),
                String.valueOf(order.getRuleVersionId()),
                String.valueOf(order.getRuleSha()));
    }

    /** 按作答聚合 4 维度得分（雷达图数据源）。分值服务端算，拒答记 0 分且分母不缩水。 */
    private List<DimScoreItem> buildDimScores(Long orderId) {
        List<EvalAnswer> answers =
                answerMapper.selectList(new QueryWrapper<EvalAnswer>().eq("order_id", orderId));
        if (answers == null) {
            answers = List.of();
        }
        Map<Integer, Integer> scoreByDim = new HashMap<>();
        Map<Integer, Integer> refuseByDim = new HashMap<>();
        Map<Long, Integer> dimByItem = loadDimensionByItem();
        for (EvalAnswer answer : answers) {
            int dim = resolveDimension(answer.getItemId(), dimByItem);
            scoreByDim.merge(dim, resolveScore(answer), Integer::sum);
            if (answer.getIsRefused() != null && answer.getIsRefused() == 1) {
                refuseByDim.merge(dim, 1, Integer::sum);
            }
        }
        List<DimScoreItem> items = new ArrayList<>();
        for (RuleSet.Dimension d : gradingService.dimensions()) {
            int dim = dimensionIndex(d.getCode());
            items.add(
                    new DimScoreItem(
                            d.getCode(),
                            d.getName(),
                            scoreByDim.getOrDefault(dim, 0),
                            d.getFullScore(),
                            refuseByDim.getOrDefault(dim, 0)));
        }
        return items;
    }

    private Map<Long, Integer> loadDimensionByItem() {
        List<EvalItem> items = itemMapper.selectList(new QueryWrapper<EvalItem>().eq("deleted", 0));
        Map<Long, Integer> dimByItem = new HashMap<>();
        for (EvalItem item : items) {
            if (item.getDimensionCode() != null) {
                dimByItem.put(item.getId(), item.getDimensionCode());
            }
        }
        return dimByItem;
    }

    /** 维度归属以题库为准（口径唯一源）；题库缺项时按指标 id 百位分区兜底。 */
    private int resolveDimension(Long itemId, Map<Long, Integer> dimByItem) {
        Integer dim = itemId == null ? null : dimByItem.get(itemId);
        return dim == null ? GradeCodeTranslator.toDimensionCode(itemId) : dim;
    }

    private int resolveScore(EvalAnswer answer) {
        return answer.getScore() == null ? 0 : answer.getScore().intValue();
    }

    /** 维度码 "D1" → 1（与 eval_item.dimension_code 的 1~4 对齐）。 */
    private int dimensionIndex(String code) {
        String digits = code.replaceAll("\\D", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }

    private void persistDimensionScores(Long reportId, List<DimScoreItem> dims) {
        for (DimScoreItem dim : dims) {
            ReportDimensionScore row = new ReportDimensionScore();
            row.setReportId(reportId);
            row.setDimensionCode(dimensionIndex(dim.getDimensionCode()));
            row.setDimensionName(dim.getDimensionName());
            row.setRawScore(BigDecimal.valueOf(dim.getScore()));
            row.setMaxScore(BigDecimal.valueOf(dim.getFullScore()));
            row.setRefuseCount(dim.getRefuseCount());
            dimScoreMapper.insert(row);
        }
    }

    private List<DimScoreItem> loadDimScores(Long reportId) {
        List<ReportDimensionScore> rows =
                dimScoreMapper.selectList(
                        new QueryWrapper<ReportDimensionScore>()
                                .eq("report_id", reportId)
                                .orderByAsc("dimension_code"));
        List<DimScoreItem> items = new ArrayList<>();
        for (ReportDimensionScore row : rows) {
            items.add(
                    new DimScoreItem(
                            "D" + row.getDimensionCode(),
                            row.getDimensionName(),
                            row.getRawScore() == null ? 0 : row.getRawScore().intValue(),
                            row.getMaxScore() == null ? 0 : row.getMaxScore().intValue(),
                            row.getRefuseCount() == null ? 0 : row.getRefuseCount()));
        }
        return items;
    }

    private String resolveVersion(Long ruleVersionId) {
        if (ruleVersionId == null) {
            return null;
        }
        GbRuleVersion v = ruleVersionMapper.selectById(ruleVersionId);
        return v == null ? null : v.getVersion();
    }

    private EvalReportResponse toResponse(EvalReport report, EvalOrder order) {
        EvalReportResponse resp = new EvalReportResponse();
        resp.setReportId(report.getId());
        resp.setReportNo(report.getReportNo());
        resp.setOrderId(report.getOrderId());
        resp.setElderId(report.getElderId());
        resp.setStatus(String.valueOf(report.getStatus()));
        resp.setTotalScore(report.getTotalScore());
        resp.setGradeCode(report.getGradeCode());
        resp.setPreliminaryGradeCode(report.getPreliminaryGradeCode());
        resp.setPreliminaryLevelName(report.getPreliminaryLevelName());
        resp.setFinalLevelCode(report.getFinalLevelCode());
        resp.setFinalLevelName(report.getFinalLevelName());
        resp.setLevelChangeReason(report.getLevelChangeReason());
        resp.setRuleVersion(resolveVersion(report.getRuleVersionId()));
        resp.setRuleSha(report.getRuleSha());
        resp.setVerifyHash(report.getVerifyHash());
        resp.setPdfCosKey(report.getPdfCosKey());
        resp.setQrContent(report.getQrContent());
        resp.setPublishedAt(report.getPublishedAt());
        resp.setDimScores(loadDimScores(report.getId()));
        if (order != null) {
            resp.setOrderStatus(order.getStatus());
        }
        return resp;
    }

    private static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
