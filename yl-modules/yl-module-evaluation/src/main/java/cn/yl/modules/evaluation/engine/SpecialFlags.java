package cn.yl.modules.evaluation.engine;

import lombok.Getter;
import lombok.Setter;

/**
 * 特殊标志（昏迷 / 痴呆 / 精神障碍 / 近 30 天风险事件次数）。
 *
 * <p>来自评估单的上下文信息（诊断编码、风险事件计数），不计入 26 项得分，但参与特殊规则判定。
 */
@Getter
@Setter
public class SpecialFlags {

    private boolean coma;
    private boolean dementia;
    private boolean mentalDisorder;
    private int riskEventCount;

    public SpecialFlags() {}

    public SpecialFlags(SpecialFlags other) {
        this.coma = other.coma;
        this.dementia = other.dementia;
        this.mentalDisorder = other.mentalDisorder;
        this.riskEventCount = other.riskEventCount;
    }
}
