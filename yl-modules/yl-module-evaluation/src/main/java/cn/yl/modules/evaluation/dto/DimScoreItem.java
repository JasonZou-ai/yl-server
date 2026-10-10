package cn.yl.modules.evaluation.dto;

/**
 * 维度得分项（雷达图数据源）。
 *
 * <p>分值一律来自服务端（引擎），四端不做本地算分——这是 PRD 红线。 {@code fullScore} 取自规则集该维度满分，供前端计算得分率。
 */
public class DimScoreItem {

    private String dimensionCode;
    private String dimensionName;
    private int score;
    private int fullScore;

    /** 该维度拒答项数（记 0 分、分母不缩水，PRD §1.4④）。 */
    private int refuseCount;

    public DimScoreItem() {}

    public DimScoreItem(String dimensionCode, String dimensionName, int score, int fullScore) {
        this(dimensionCode, dimensionName, score, fullScore, 0);
    }

    public DimScoreItem(
            String dimensionCode, String dimensionName, int score, int fullScore, int refuseCount) {
        this.dimensionCode = dimensionCode;
        this.dimensionName = dimensionName;
        this.score = score;
        this.fullScore = fullScore;
        this.refuseCount = refuseCount;
    }

    public String getDimensionCode() {
        return dimensionCode;
    }

    public void setDimensionCode(String dimensionCode) {
        this.dimensionCode = dimensionCode;
    }

    public String getDimensionName() {
        return dimensionName;
    }

    public void setDimensionName(String dimensionName) {
        this.dimensionName = dimensionName;
    }

    public int getScore() {
        return score;
    }

    public void setScore(int score) {
        this.score = score;
    }

    public int getFullScore() {
        return fullScore;
    }

    public void setFullScore(int fullScore) {
        this.fullScore = fullScore;
    }

    public int getRefuseCount() {
        return refuseCount;
    }

    public void setRefuseCount(int refuseCount) {
        this.refuseCount = refuseCount;
    }
}
