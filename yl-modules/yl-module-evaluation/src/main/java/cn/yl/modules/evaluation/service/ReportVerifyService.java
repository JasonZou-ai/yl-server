package cn.yl.modules.evaluation.service;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.evaluation.domain.entity.EvalOrder;
import cn.yl.modules.evaluation.domain.entity.EvalReport;
import cn.yl.modules.evaluation.dto.ReportVerifyResponse;
import cn.yl.modules.evaluation.mapper.EvalOrderMapper;
import cn.yl.modules.evaluation.mapper.EvalReportMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * B8 报告验真服务（{@code GET /api/v1/reports/verify}，public 端点）。
 *
 * <p><b>public 端点的数据域特殊性</b>：无登录态 → 第三闸 {@code DataScopePermissionHandler.currentUser()==null} →
 * 不加限。 故可见性<b>不能</b>依赖第三闸，而由本服务的「最小返回面」（只返 {@code {valid, gradeCode, publishedAt}}，不返任何个人信息）保证。
 *
 * <p><b>验真口径</b>：{@code verify_hash} 的计算源是 {@code eval_order}（非 {@code eval_report} 行）。命中报告后按其
 * {@code order_id} 取评估单，<b>重算</b> {@code sha256(canonicalContent(order))} 与 {@code
 * report.verify_hash} 比对。 {@code canonicalContent} 与 {@code AssessmentReportService} <b>逐字段一致</b>（8
 * 字段 {@code |} join），由单测钉死。
 *
 * <p>未命中 → 返回 {@code valid=false}（<b>不泄露存在性</b>）。批量枚举由既有 {@code RateLimitInterceptor} 兜底。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportVerifyService {

    /** 报告「有效」状态（0-生成中 1-有效 2-已作废）。 */
    private static final int REPORT_VALID = 1;

    /** 报告编号白名单：与报告生成规则（{@code RP+uuid}）及 {@code ReportPdfService} 同口径。 */
    private static final Pattern REPORT_NO = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    /** 完整 QR URL 中的编号参数：{@code ...?no=RPxxx&hash=...}。 */
    private static final Pattern NO_PARAM = Pattern.compile("[?&]no=([A-Za-z0-9_-]{1,64})");

    private final EvalReportMapper reportMapper;
    private final EvalOrderMapper orderMapper;

    /**
     * 验真。
     *
     * @param code 扫码内容：兼容「纯 reportNo」与「完整 QR URL」
     * @return 最小返回面 {@code {valid, gradeCode, publishedAt}}
     */
    public ReportVerifyResponse verify(String code) {
        String reportNo = normalizeReportNo(code);
        EvalReport report =
                reportMapper.selectOne(
                        Wrappers.<EvalReport>lambdaQuery().eq(EvalReport::getReportNo, reportNo));
        if (report == null) {
            // 不泄露存在性：不存在与"无效"同形
            return new ReportVerifyResponse(false, null, null);
        }
        EvalOrder order =
                report.getOrderId() == null ? null : orderMapper.selectById(report.getOrderId());
        boolean hashMatches =
                order != null
                        && report.getVerifyHash() != null
                        && report.getVerifyHash().equals(sha256(canonicalContent(order)));
        boolean valid =
                report.getStatus() != null && report.getStatus() == REPORT_VALID && hashMatches;
        return new ReportVerifyResponse(valid, report.getGradeCode(), report.getPublishedAt());
    }

    /**
     * 归一化 {@code code} → {@code reportNo}：先按纯编号匹配，再兼容完整 QR URL 的 {@code no} 参数。
     *
     * <p>白名单 {@code [A-Za-z0-9_-]{1,64}}，非法/空 → {@link ErrorCode#PARAM_INVALID}。
     */
    private String normalizeReportNo(String code) {
        if (code == null || code.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "code 不能为空");
        }
        String trimmed = code.trim();
        if (REPORT_NO.matcher(trimmed).matches()) {
            return trimmed;
        }
        Matcher matcher = NO_PARAM.matcher(trimmed);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "验真 code 非法");
    }

    /**
     * 报告内容摘要输入串 —— 与 {@code AssessmentReportService#canonicalContent} <b>逐字段一致</b>。
     *
     * <p>8 字段 {@code |} join：orderNo / totalScore / gradeCode / preliminaryGradeCode /
     * finalLevelCode / gradeChangeReason / ruleVersionId / ruleSha。
     */
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
