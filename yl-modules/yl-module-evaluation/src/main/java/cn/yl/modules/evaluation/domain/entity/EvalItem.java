package cn.yl.modules.evaluation.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import lombok.Data;

/**
 * 指标题库（eval_item，26 项二级指标）。
 *
 * <p>由 02_schema.sql 按权威规则集播种：{@code id} 即引擎指标 id（101~405），{@code scoreMax} 合计 90 ＝ 规则集 fullScore。
 *
 * <p>{@code isRequired}=1 表示必答——应填未填即阻断提交（ER-06），由提交前校验强制执行。
 */
@Data
@TableName("eval_item")
public class EvalItem {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long ruleVersionId;

    /** 1-自理能力 2-基础运动能力 3-精神状态 4-感知觉与社会参与。 */
    private Integer dimensionCode;

    private String itemCode;
    private String itemName;
    private String itemDesc;
    private BigDecimal scoreMin;
    private BigDecimal scoreMax;
    private BigDecimal weight;

    /** 1-关键指标强制留证。 */
    private Integer needEvidence;

    /** 1-必答（应填未填即阻断提交，ER-06）。 */
    private Integer isRequired;

    private Integer orderNo;
    private Integer deleted;
}
