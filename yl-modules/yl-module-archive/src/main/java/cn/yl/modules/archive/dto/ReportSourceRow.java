package cn.yl.modules.archive.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * {@code eval_report} 只读投影行（D-4）。
 *
 * <p>列名逐列取自 {@code docker/mysql/init/02_schema.sql} 的 {@code eval_report} 表。<b>不引用</b>评估/报表模块的实体类。
 *
 * <p>{@code selectPublishedPage} 仅填充列表所需字段；{@code selectReportById} 额外填充详情头字段（{@code orderId} /
 * {@code levelName} 系列 / {@code status} 等）。
 */
@Data
public class ReportSourceRow {

    /** 报告 id（列表 SQL 用 {@code r.id AS report_id} 别名映射）。 */
    private Long reportId;

    private String reportNo;

    private Long elderId;

    private Long orgId;

    /** 等级码（GRADE_ 前缀）。 */
    private String gradeCode;

    /** 总分（DECIMAL(6,2)）。 */
    private BigDecimal totalScore;

    private LocalDateTime publishedAt;

    // ---- 以下仅 selectReportById 填充（详情头） ----

    private Long orderId;

    private Long ruleVersionId;

    private String preliminaryGradeCode;

    private String preliminaryLevelName;

    private String finalLevelCode;

    private String finalLevelName;

    private String levelChangeReason;

    /** 0-生成中 1-有效 2-已作废。 */
    private Integer status;
}
