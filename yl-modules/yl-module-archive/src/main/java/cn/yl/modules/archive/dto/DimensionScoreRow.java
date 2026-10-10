package cn.yl.modules.archive.dto;

import java.math.BigDecimal;
import lombok.Data;

/** {@code report_dimension_score} 只读投影行（D-4；该表无 {@code deleted} 列）。 */
@Data
public class DimensionScoreRow {

    /** 维度码（TINYINT）。 */
    private Integer dimensionCode;

    private String dimensionName;

    /** 维度原始得分（DECIMAL(6,2)）。 */
    private BigDecimal rawScore;

    /** 维度满分（DECIMAL(6,2)）。 */
    private BigDecimal maxScore;

    /** 拒答条目数。 */
    private Integer refuseCount;
}
