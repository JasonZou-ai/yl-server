package cn.yl.modules.evaluation.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 评估作答明细（eval_answer，ER-05 / ER-06 作答三语义）。
 *
 * <p>{@code itemId} 直接取引擎指标 id（101~405），与 eval_item.id 同源，提交时无需二次映射。
 *
 * <p>answerState：0-未作答 1-已作答 2-不适用(分支未触达) 3-拒答。拒答 {@code score} 为 NULL 且 {@code isRefused}=1， 按规则集
 * abstention.mode=zero 计 0 分并计入 refuseCount。
 *
 * <p>本表无 {@code deleted} 列，故不映射软删字段（MyBatis-Plus 仅对存在该属性的实体追加逻辑删除条件）。
 */
@Data
@TableName("eval_answer")
public class EvalAnswer {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long orderId;
    private Long itemId;

    /** 冗余维度，便于维度汇总：1-自理 2-基础运动 3-精神状态 4-感知觉与社会参与。 */
    private Integer dimensionCode;

    private String optionCode;

    /** 本条得分；拒答为 NULL。 */
    private BigDecimal score;

    private Integer isRefused;
    private Integer answerState;
    private Integer hasEvidence;

    /** 作答耗时（埋点分析）。 */
    private Integer costMs;

    private LocalDateTime answeredAt;

    /** 乐观锁版本号（离线冲突检测）。 */
    private Integer rowVersion;
}
