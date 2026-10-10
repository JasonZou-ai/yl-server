package cn.yl.modules.evaluation.service;

import cn.yl.modules.evaluation.engine.Answer;
import cn.yl.modules.evaluation.engine.GradeResult;
import cn.yl.modules.evaluation.engine.GradingEngine;
import cn.yl.modules.evaluation.engine.RuleMeta;
import cn.yl.modules.evaluation.engine.RuleSet;
import cn.yl.modules.evaluation.engine.RuleSetLoader;
import cn.yl.modules.evaluation.engine.RuleSetValidator;
import cn.yl.modules.evaluation.engine.SpecialFlags;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 评估分级服务（Spring Bean）。
 *
 * <p>构造期即从 classpath 加载规则集（计算 SHA-256 指纹并校验），对外提供评分与版本元信息。规则集为不可变字段。 报告侧应持久化每次评分返回的 {@code
 * ruleVersion}/{@code ruleSha} 以支持事后复算。
 */
@Service
@SuppressFBWarnings(
        value = "CT_CONSTRUCTOR_THROW",
        justification =
                "规则集为本地 classpath 资源，加载失败属启动期致命错误，抛 IllegalStateException 由 Spring 容器终止上下文，无 Finalizer 攻击面")
public class AssessmentGradingService {

    private static final String RULESET_RESOURCE = "rules/gbt42195-ruleset.json";

    private final RuleSet ruleSet = loadRuleSet();

    public GradeResult grade(List<Answer> answers, SpecialFlags flags) {
        return GradingEngine.grade(ruleSet, answers == null ? List.of() : answers, flags);
    }

    /** 规则集维度定义（编码 / 名称 / 满分），供结果组装维度得分明细。返回不可变副本。 */
    public List<RuleSet.Dimension> dimensions() {
        return ruleSet.getDimensions();
    }

    public RuleMeta meta() {
        List<RuleMeta.DimensionMeta> dims = new ArrayList<>();
        for (RuleSet.Dimension d : ruleSet.getDimensions()) {
            dims.add(
                    new RuleMeta.DimensionMeta(
                            d.getCode(), d.getName(), d.getFullScore(), d.getIndicatorCount()));
        }
        return new RuleMeta(
                ruleSet.getRuleVersion(),
                ruleSet.getRuleSha(),
                ruleSet.getStatus(),
                ruleSet.getDataKind(),
                ruleSet.getFullScore(),
                ruleSet.getLevels().size(),
                ruleSet.getIndicators().size(),
                dims);
    }

    private static RuleSet loadRuleSet() {
        try {
            RuleSet rs = RuleSetLoader.loadFromClasspath(RULESET_RESOURCE);
            RuleSetValidator.validate(rs);
            return rs;
        } catch (IOException e) {
            throw new IllegalStateException("加载分级规则集失败: " + RULESET_RESOURCE, e);
        }
    }
}
