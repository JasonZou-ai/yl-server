package cn.yl.modules.evaluation.dto;

import java.math.BigDecimal;
import lombok.Data;

/**
 * 单项作答输入。
 *
 * <p>{@code itemId} 即引擎指标 id（101~405），与 eval_item.id 同源。
 *
 * <p>拒答语义（ER-06）：{@code refused}=true 时 {@code score} 应为 null，计 0 分并计入 refuseCount 触发人工复核。 {@code
 * answerState}：0-未作答 1-已作答 2-不适用 3-拒答；缺省按 1 处理。
 */
@Data
public class AnswerInput {

    private Long itemId;
    private BigDecimal score;
    private String optionCode;
    private Boolean refused;
    private Integer answerState;
    private Boolean hasEvidence;
    private Integer costMs;
}
