package cn.yl.modules.evaluation.dto;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * B8 二维码验真返回（{@code GET /api/v1/reports/verify}）。
 *
 * <p><b>最小返回面（裁决 4）</b>：<b>仅</b>此三字段 —— {@code valid} / {@code gradeCode} / {@code publishedAt}。
 * <b>绝不返回</b>姓名、身份证、手机号等任何个人信息字段。本类字段集合由 {@code ReportVerifyServiceTest} 钉死。
 */
@Data
public class ReportVerifyResponse {

    /** 报告是否有效（已发布 + 内容摘要一致）。 */
    private boolean valid;

    /** 终判等级码（无效/不存在时为 null）。 */
    private String gradeCode;

    /** 报告出具时间（无效/不存在时为 null）。 */
    private LocalDateTime publishedAt;

    public ReportVerifyResponse() {}

    public ReportVerifyResponse(boolean valid, String gradeCode, LocalDateTime publishedAt) {
        this.valid = valid;
        this.gradeCode = gradeCode;
        this.publishedAt = publishedAt;
    }
}
