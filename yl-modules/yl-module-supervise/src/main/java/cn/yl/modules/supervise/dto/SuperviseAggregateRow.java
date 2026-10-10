package cn.yl.modules.supervise.dto;

import lombok.Data;

/** B10 监管上报聚合行（评估域计数，payload 数据源）。 */
@Data
public class SuperviseAggregateRow {

    /** 当月在册评估单数。 */
    private Long assessmentCount;

    /** 当月已发布评估单数。 */
    private Long publishedCount;

    /** 当月能力完好（GRADE_INTACT）数。 */
    private Long gradeIntactCount;

    /** 当月重度及以上（GRADE_SEVERE/GRADE_TOTAL）数。 */
    private Long gradeSevereCount;
}
