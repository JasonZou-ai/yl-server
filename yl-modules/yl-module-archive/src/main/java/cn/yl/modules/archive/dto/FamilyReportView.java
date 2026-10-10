package cn.yl.modules.archive.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 报告列表行（D-4 家属端 {@code GET /family/bindings/{relationId}/reports}）。
 *
 * <p>仅暴露列表展示所需字段（无老人明文个人信息列）。
 */
@Data
public class FamilyReportView {

    private Long reportId;

    private String reportNo;

    private Long elderId;

    /** 等级码（GRADE_INTACT/MILD/MODERATE/SEVERE/TOTAL）。 */
    private String gradeCode;

    private BigDecimal totalScore;

    private LocalDateTime publishedAt;
}
