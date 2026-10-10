package cn.yl.modules.evaluation.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/**
 * 报告维度得分（report_dimension_score，雷达图数据源）。
 *
 * <p>由报告生成时按 {@code eval_answer} 聚合得到：{@code rawScore} 为该维度实得， {@code maxScore} 取自规则集维度满分（与 {@code
 * refuseCount} 一起支撑「拒答记 0 分、分母不缩水」的可解释展示）。
 *
 * <p>本表无 {@code deleted} 列（随报告软删一起失效），故不映射软删字段。
 */
@Data
@TableName("report_dimension_score")
public class ReportDimensionScore {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long reportId;

    /** 1-自理能力 2-基础运动能力 3-精神状态 4-感知觉与社会参与。 */
    private Integer dimensionCode;

    private String dimensionName;
    private BigDecimal rawScore;
    private BigDecimal maxScore;

    /** 该维度拒答项数（记 0 分，分母不缩水）。 */
    private Integer refuseCount;
}
