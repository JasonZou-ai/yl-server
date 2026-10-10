package cn.yl.modules.evaluation.engine;

import java.util.List;
import lombok.Getter;

/**
 * 评分可追溯轨迹：实际命中的规则、是否触发昏迷直判、是否触发升级。
 *
 * <p>字段均为不可变副本，供报告与审计留痕（事后复算时核对判定路径）。
 */
@Getter
public class GradeTrace {

    private final List<String> appliedRules;
    private final Boolean override;
    private final boolean escalated;

    public GradeTrace(List<String> appliedRules, Boolean override, boolean escalated) {
        this.appliedRules = List.copyOf(appliedRules);
        this.override = override;
        this.escalated = escalated;
    }
}
