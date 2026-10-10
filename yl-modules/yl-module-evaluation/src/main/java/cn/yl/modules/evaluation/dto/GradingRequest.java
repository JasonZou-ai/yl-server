package cn.yl.modules.evaluation.dto;

import cn.yl.modules.evaluation.engine.Answer;
import cn.yl.modules.evaluation.engine.SpecialFlags;
import java.util.List;

/**
 * 评分请求体：26 项指标答题明细 + 特殊标志。
 *
 * <p>flags 可省略（缺省视为无特殊标志）；answers 为 null 时按「全部 0 分」处理（即能力完全丧失）。
 * 集合与对象字段采用防御性拷贝，避免暴露内部可变表示（EI_EXPOSE_REP）。
 */
public class GradingRequest {

    private List<Answer> answers = List.of();
    private SpecialFlags flags;

    public List<Answer> getAnswers() {
        return List.copyOf(answers);
    }

    public void setAnswers(List<Answer> answers) {
        this.answers = answers == null ? List.of() : List.copyOf(answers);
    }

    public SpecialFlags getFlags() {
        return flags == null ? null : new SpecialFlags(flags);
    }

    public void setFlags(SpecialFlags flags) {
        this.flags = flags == null ? null : new SpecialFlags(flags);
    }
}
