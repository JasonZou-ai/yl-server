package cn.yl.modules.evaluation.engine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 数据驱动分级引擎（GB/T 42195-2022）。
 *
 * <p>所有判定语义来自 {@link RuleSet} JSON：维度聚合 -> 总分 -> 初判 -> 特殊规则（昏迷直判优先 / 升级封顶）-> 终判。
 * 引擎不含任何国标阈值常量；改口径只换规则数据文件。
 */
public final class GradingEngine {

    private static final List<String> DIM_ORDER = List.of("D1", "D2", "D3", "D4");

    private GradingEngine() {}

    /**
     * 评分主入口。
     *
     * @param ruleset 已加载并附加 ruleSha 的规则集
     * @param answers 答题明细（indicatorId -> score）
     * @param flags 特殊标志（昏迷 / 痴呆 / 精神障碍 / 风险事件次数），null 视为全 false / 0
     */
    public static GradeResult grade(RuleSet ruleset, List<Answer> answers, SpecialFlags flags) {
        SpecialFlags f = flags == null ? new SpecialFlags() : flags;
        Map<String, Integer> dimScores = aggregateDimScores(ruleset, answers);

        int total = 0;
        for (String d : DIM_ORDER) {
            total += dimScores.getOrDefault(d, 0);
        }

        RuleSet.Level prelim = levelForTotal(ruleset, total);

        FinalResolution res = resolveFinalLevel(ruleset, prelim.getSeverity(), f);
        int finalLevel = res.level();

        // levelChangeReason 仅在等级真实变化时写入；命中但被封顶（等级未变）只在 trace 留痕
        String reason = null;
        if (finalLevel != prelim.getSeverity()) {
            reason = res.override() ? "coma" : String.join(",", res.appliedRules());
        }

        GradeTrace trace = new GradeTrace(res.appliedRules(), res.override(), res.escalated());
        RuleSet.Level finalLvl = levelForSeverity(ruleset, finalLevel);
        return new GradeResult(
                ruleset.getRuleVersion(),
                ruleset.getRuleSha(),
                total,
                dimScores,
                prelim.getCode(),
                prelim.getSeverity(),
                prelim.getName(),
                finalLvl.getCode(),
                finalLvl.getSeverity(),
                finalLvl.getName(),
                finalLevel - prelim.getSeverity(),
                reason,
                trace);
    }

    private static Map<String, Integer> aggregateDimScores(RuleSet ruleset, List<Answer> answers) {
        Map<Integer, RuleSet.Indicator> indMap = new HashMap<>();
        for (RuleSet.Indicator ind : ruleset.getIndicators()) {
            indMap.put(ind.getId(), ind);
        }
        Map<String, Integer> dimScores = new HashMap<>();
        dimScores.put("D1", 0);
        dimScores.put("D2", 0);
        dimScores.put("D3", 0);
        dimScores.put("D4", 0);
        for (Answer a : answers) {
            RuleSet.Indicator ind = indMap.get(a.getIndicatorId());
            if (ind == null) {
                throw new IllegalArgumentException("未知指标ID: " + a.getIndicatorId());
            }
            int score = a.getScore();
            if (score < 0 || score > ind.getFullScore()) {
                throw new IllegalArgumentException(
                        "指标 "
                                + a.getIndicatorId()
                                + "（"
                                + ind.getName()
                                + "）分值越界: "
                                + score
                                + "，满分 "
                                + ind.getFullScore());
            }
            dimScores.merge(ind.getDimension(), score, Integer::sum);
        }
        return dimScores;
    }

    private static FinalResolution resolveFinalLevel(
            RuleSet ruleset, int prelimSeverity, SpecialFlags f) {
        List<String> appliedRules = new ArrayList<>();
        boolean override = false;
        boolean escalated = false;
        int finalLevel = prelimSeverity;

        RuleSet.SpecialRule comaRule = findComa(ruleset);
        if (f.isComa() && comaRule != null) {
            // 说明1：昏迷直判（优先级最高），封顶 maxLevel
            appliedRules.add("coma");
            override = true;
            int target =
                    comaRule.getTargetLevel() != null
                            ? comaRule.getTargetLevel()
                            : ruleset.getSpecialRules().getMaxLevel();
            finalLevel = Math.min(target, ruleset.getSpecialRules().getMaxLevel());
        } else {
            // 说明2：升级（any 模式，命中任一即 +1，不叠加，封顶 maxLevel）
            List<String> matched = new ArrayList<>();
            for (RuleSet.SpecialRule r : ruleset.getSpecialRules().getEscalations()) {
                if (matchRule(r, f)) {
                    matched.add(r.getId());
                    appliedRules.add(r.getId());
                }
            }
            if (!matched.isEmpty()) {
                escalated = true;
                finalLevel = Math.min(prelimSeverity + 1, ruleset.getSpecialRules().getMaxLevel());
            }
        }
        return new FinalResolution(finalLevel, appliedRules, override, escalated);
    }

    private static RuleSet.SpecialRule findComa(RuleSet ruleset) {
        for (RuleSet.SpecialRule r : ruleset.getSpecialRules().getOverrides()) {
            if ("coma".equals(r.getId())) {
                return r;
            }
        }
        return null;
    }

    private static boolean matchRule(RuleSet.SpecialRule r, SpecialFlags f) {
        RuleSet.SpecialRuleMatch m = r.getMatch();
        if (m == null) {
            return false;
        }
        Object v =
                switch (m.getFlag()) {
                    case "dementia" -> f.isDementia();
                    case "mentalDisorder" -> f.isMentalDisorder();
                    case "riskEventCount" -> f.getRiskEventCount();
                    default -> null;
                };
        if ("gte".equals(m.getOp())) {
            return v instanceof Integer i && i >= (m.getValue() != null ? m.getValue() : 0);
        }
        if (m.getEquals() != null) {
            return m.getEquals().equals(v);
        }
        return false;
    }

    private static RuleSet.Level levelForTotal(RuleSet ruleset, int total) {
        for (RuleSet.Level l : ruleset.getLevels()) {
            if (total >= l.getMin() && total <= l.getMax()) {
                return l;
            }
        }
        return ruleset.getLevels().get(ruleset.getLevels().size() - 1);
    }

    private static RuleSet.Level levelForSeverity(RuleSet ruleset, int severity) {
        for (RuleSet.Level l : ruleset.getLevels()) {
            if (l.getSeverity() == severity) {
                return l;
            }
        }
        return ruleset.getLevels().get(ruleset.getLevels().size() - 1);
    }

    private record FinalResolution(
            int level, List<String> appliedRules, boolean override, boolean escalated) {}
}
