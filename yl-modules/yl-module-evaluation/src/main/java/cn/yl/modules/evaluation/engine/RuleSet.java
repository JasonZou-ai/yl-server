package cn.yl.modules.evaluation.engine;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * 分级规则集（数据驱动）。
 *
 * <p>所有判定阈值、维度满分、等级区间与特殊规则均来自 JSON，引擎不含任何国标常量；改口径只换数据文件。
 *
 * <p>集合字段统一采用防御性拷贝（getter 返回不可变副本、setter 存拷贝），避免暴露内部可变表示（EI_EXPOSE_REP）。 {@code specialRules}
 * 为只读配置对象，由 Jackson 从可信 JSON 反序列化且仅被引擎读取；其 getter/setter 均通过 {@link SpecialRules} 拷贝构造器做深度防御性拷贝。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RuleSet {

    private String standard;
    private String ruleVersion;
    private String status;
    private String dataKind;
    private int fullScore;

    /** 加载后由 {@link RuleSetLoader} 写入的 SHA-256 内容指纹，不参与 JSON 序列化。 */
    @JsonIgnore private String ruleSha;

    private List<Dimension> dimensions = new ArrayList<>();
    private List<Indicator> indicators = new ArrayList<>();
    private List<Level> levels = new ArrayList<>();
    private SpecialRules specialRules;

    public String getStandard() {
        return standard;
    }

    public void setStandard(String standard) {
        this.standard = standard;
    }

    public String getRuleVersion() {
        return ruleVersion;
    }

    public void setRuleVersion(String ruleVersion) {
        this.ruleVersion = ruleVersion;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getDataKind() {
        return dataKind;
    }

    public void setDataKind(String dataKind) {
        this.dataKind = dataKind;
    }

    public int getFullScore() {
        return fullScore;
    }

    public void setFullScore(int fullScore) {
        this.fullScore = fullScore;
    }

    public String getRuleSha() {
        return ruleSha;
    }

    public void setRuleSha(String ruleSha) {
        this.ruleSha = ruleSha;
    }

    public List<Dimension> getDimensions() {
        return List.copyOf(dimensions);
    }

    public void setDimensions(List<Dimension> dimensions) {
        this.dimensions = dimensions == null ? new ArrayList<>() : new ArrayList<>(dimensions);
    }

    public List<Indicator> getIndicators() {
        return List.copyOf(indicators);
    }

    public void setIndicators(List<Indicator> indicators) {
        this.indicators = indicators == null ? new ArrayList<>() : new ArrayList<>(indicators);
    }

    public List<Level> getLevels() {
        return List.copyOf(levels);
    }

    public void setLevels(List<Level> levels) {
        this.levels = levels == null ? new ArrayList<>() : new ArrayList<>(levels);
    }

    public SpecialRules getSpecialRules() {
        return specialRules == null ? null : new SpecialRules(specialRules);
    }

    public void setSpecialRules(SpecialRules specialRules) {
        this.specialRules = specialRules == null ? null : new SpecialRules(specialRules);
    }

    public static class Dimension {
        private String code;
        private String name;
        private int fullScore;
        private int indicatorCount;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getFullScore() {
            return fullScore;
        }

        public void setFullScore(int fullScore) {
            this.fullScore = fullScore;
        }

        public int getIndicatorCount() {
            return indicatorCount;
        }

        public void setIndicatorCount(int indicatorCount) {
            this.indicatorCount = indicatorCount;
        }
    }

    public static class Indicator {
        private int id;
        private String dimension;
        private String name;
        private int fullScore;

        public int getId() {
            return id;
        }

        public void setId(int id) {
            this.id = id;
        }

        public String getDimension() {
            return dimension;
        }

        public void setDimension(String dimension) {
            this.dimension = dimension;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getFullScore() {
            return fullScore;
        }

        public void setFullScore(int fullScore) {
            this.fullScore = fullScore;
        }
    }

    public static class Level {
        private String code;
        private String name;
        private int min;
        private int max;
        private int severity;

        public String getCode() {
            return code;
        }

        public void setCode(String code) {
            this.code = code;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getMin() {
            return min;
        }

        public void setMin(int min) {
            this.min = min;
        }

        public int getMax() {
            return max;
        }

        public void setMax(int max) {
            this.max = max;
        }

        public int getSeverity() {
            return severity;
        }

        public void setSeverity(int severity) {
            this.severity = severity;
        }
    }

    public static class SpecialRules {
        private int maxLevel;
        private String escalateMode;
        private List<SpecialRule> overrides = new ArrayList<>();
        private List<SpecialRule> escalations = new ArrayList<>();

        public SpecialRules() {}

        public SpecialRules(SpecialRules other) {
            if (other == null) {
                return;
            }
            this.maxLevel = other.maxLevel;
            this.escalateMode = other.escalateMode;
            if (other.overrides != null) {
                for (SpecialRule r : other.overrides) {
                    this.overrides.add(new SpecialRule(r));
                }
            }
            if (other.escalations != null) {
                for (SpecialRule r : other.escalations) {
                    this.escalations.add(new SpecialRule(r));
                }
            }
        }

        public int getMaxLevel() {
            return maxLevel;
        }

        public void setMaxLevel(int maxLevel) {
            this.maxLevel = maxLevel;
        }

        public String getEscalateMode() {
            return escalateMode;
        }

        public void setEscalateMode(String escalateMode) {
            this.escalateMode = escalateMode;
        }

        public List<SpecialRule> getOverrides() {
            return List.copyOf(overrides);
        }

        public void setOverrides(List<SpecialRule> overrides) {
            this.overrides = overrides == null ? new ArrayList<>() : new ArrayList<>(overrides);
        }

        public List<SpecialRule> getEscalations() {
            return List.copyOf(escalations);
        }

        public void setEscalations(List<SpecialRule> escalations) {
            this.escalations =
                    escalations == null ? new ArrayList<>() : new ArrayList<>(escalations);
        }
    }

    public static class SpecialRule {
        private String id;
        private String type;
        private Integer targetLevel;
        private Integer step;
        private int priority;
        private String description;
        private SpecialRuleMatch match;

        public SpecialRule() {}

        public SpecialRule(SpecialRule other) {
            this.id = other.id;
            this.type = other.type;
            this.targetLevel = other.targetLevel;
            this.step = other.step;
            this.priority = other.priority;
            this.description = other.description;
            this.match = other.match == null ? null : new SpecialRuleMatch(other.match);
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public Integer getTargetLevel() {
            return targetLevel;
        }

        public void setTargetLevel(Integer targetLevel) {
            this.targetLevel = targetLevel;
        }

        public Integer getStep() {
            return step;
        }

        public void setStep(Integer step) {
            this.step = step;
        }

        public int getPriority() {
            return priority;
        }

        public void setPriority(int priority) {
            this.priority = priority;
        }

        public String getDescription() {
            return description;
        }

        public void setDescription(String description) {
            this.description = description;
        }

        public SpecialRuleMatch getMatch() {
            return match == null ? null : new SpecialRuleMatch(match);
        }

        public void setMatch(SpecialRuleMatch match) {
            this.match = match == null ? null : new SpecialRuleMatch(match);
        }
    }

    public static class SpecialRuleMatch {
        private String flag;
        private Boolean equals;
        private String op;
        private Integer value;

        public SpecialRuleMatch() {}

        public SpecialRuleMatch(SpecialRuleMatch other) {
            this.flag = other.flag;
            this.equals = other.equals;
            this.op = other.op;
            this.value = other.value;
        }

        public String getFlag() {
            return flag;
        }

        public void setFlag(String flag) {
            this.flag = flag;
        }

        public Boolean getEquals() {
            return equals;
        }

        public void setEquals(Boolean equals) {
            this.equals = equals;
        }

        public String getOp() {
            return op;
        }

        public void setOp(String op) {
            this.op = op;
        }

        public Integer getValue() {
            return value;
        }

        public void setValue(Integer value) {
            this.value = value;
        }
    }
}
