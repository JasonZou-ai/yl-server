package cn.yl.modules.evaluation.engine;

import java.util.Map;
import lombok.Getter;

/**
 * 单次评分结果（GB/T 42195-2022）。
 *
 * <p>含维度明细、初判/终判等级、等级变化原因与可追溯轨迹；所有字段为不可变副本，一次性序列化返回。
 */
@Getter
public class GradeResult {

    private final String ruleVersion;
    private final String ruleSha;
    private final int totalScore;
    private final Map<String, Integer> dimScores;
    private final String preliminaryLevelCode;
    private final int preliminaryLevel;
    private final String preliminaryName;
    private final String levelCode;
    private final int level;
    private final String levelName;
    private final int severityChange;
    private final String levelChangeReason;
    private final GradeTrace trace;

    public GradeResult(
            String ruleVersion,
            String ruleSha,
            int totalScore,
            Map<String, Integer> dimScores,
            String preliminaryLevelCode,
            int preliminaryLevel,
            String preliminaryName,
            String levelCode,
            int level,
            String levelName,
            int severityChange,
            String levelChangeReason,
            GradeTrace trace) {
        this.ruleVersion = ruleVersion;
        this.ruleSha = ruleSha;
        this.totalScore = totalScore;
        this.dimScores = Map.copyOf(dimScores);
        this.preliminaryLevelCode = preliminaryLevelCode;
        this.preliminaryLevel = preliminaryLevel;
        this.preliminaryName = preliminaryName;
        this.levelCode = levelCode;
        this.level = level;
        this.levelName = levelName;
        this.severityChange = severityChange;
        this.levelChangeReason = levelChangeReason;
        this.trace = trace;
    }
}
