package cn.yl.modules.evaluation.engine;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 规则集结构校验（对照权威源 GB/T 42195-2022 表6）。
 *
 * <p>校验维度满分之和、指标满分之和、各维度指标数、等级区间在 0..fullScore 连续无重叠、severity 0..4 唯一、 封顶等级为 4、且至少存在 coma
 * 直判规则。任一不符即抛 {@link IllegalArgumentException}。
 */
public final class RuleSetValidator {

    private static final int EXPECTED_MAX_LEVEL = 4;

    private RuleSetValidator() {}

    public static void validate(RuleSet rs) {
        validateSums(rs);
        validateIndicatorCounts(rs);
        validateLevelContiguity(rs);
        validateSeverities(rs);
        validateSpecialRules(rs);
    }

    private static void validateSums(RuleSet rs) {
        int dimSum = rs.getDimensions().stream().mapToInt(RuleSet.Dimension::getFullScore).sum();
        if (dimSum != rs.getFullScore()) {
            throw new IllegalArgumentException(
                    "维度满分之和(" + dimSum + ")不等于 fullScore(" + rs.getFullScore() + ")");
        }
        int indSum = rs.getIndicators().stream().mapToInt(RuleSet.Indicator::getFullScore).sum();
        if (indSum != rs.getFullScore()) {
            throw new IllegalArgumentException(
                    "指标满分之和(" + indSum + ")不等于 fullScore(" + rs.getFullScore() + ")");
        }
    }

    private static void validateIndicatorCounts(RuleSet rs) {
        Map<String, Long> cnt =
                rs.getIndicators().stream()
                        .collect(
                                Collectors.groupingBy(
                                        RuleSet.Indicator::getDimension, Collectors.counting()));
        for (RuleSet.Dimension d : rs.getDimensions()) {
            long actual = cnt.getOrDefault(d.getCode(), 0L);
            if (actual != d.getIndicatorCount()) {
                throw new IllegalArgumentException(
                        "维度 "
                                + d.getCode()
                                + " 指标数不符：期望 "
                                + d.getIndicatorCount()
                                + " 实际 "
                                + actual);
            }
        }
    }

    private static void validateLevelContiguity(RuleSet rs) {
        boolean[] covered = new boolean[rs.getFullScore() + 1];
        for (RuleSet.Level l : rs.getLevels()) {
            if (l.getMin() < 0 || l.getMax() > rs.getFullScore() || l.getMin() > l.getMax()) {
                throw new IllegalArgumentException("等级区间非法: " + l.getCode());
            }
            for (int s = l.getMin(); s <= l.getMax(); s++) {
                if (covered[s]) {
                    throw new IllegalArgumentException("等级区间重叠于总分 " + s);
                }
                covered[s] = true;
            }
        }
        for (int s = 0; s <= rs.getFullScore(); s++) {
            if (!covered[s]) {
                throw new IllegalArgumentException("等级区间未覆盖总分 " + s);
            }
        }
    }

    private static void validateSeverities(RuleSet rs) {
        Set<Integer> severities = new HashSet<>();
        for (RuleSet.Level l : rs.getLevels()) {
            severities.add(l.getSeverity());
        }
        for (int i = 0; i <= EXPECTED_MAX_LEVEL; i++) {
            if (!severities.contains(i)) {
                throw new IllegalArgumentException("缺少 severity=" + i + " 的等级");
            }
        }
    }

    private static void validateSpecialRules(RuleSet rs) {
        if (rs.getSpecialRules().getMaxLevel() != EXPECTED_MAX_LEVEL) {
            throw new IllegalArgumentException("specialRules.maxLevel 必须为 " + EXPECTED_MAX_LEVEL);
        }
        boolean hasComa =
                rs.getSpecialRules().getOverrides().stream()
                        .anyMatch(r -> "coma".equals(r.getId()) && "direct".equals(r.getType()));
        if (!hasComa) {
            throw new IllegalArgumentException("缺少 coma 直判规则（type=direct）");
        }
    }
}
