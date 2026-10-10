package cn.yl.modules.archive.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 报告详情（D-4 家属端 {@code GET /family/bindings/{relationId}/reports/{reportId}}）。
 *
 * <p>{@code careItems} 来自 {@code care_plan.items_json}（Jackson 解为字符串列表；空/异常 → 空列表，<b>不造数</b>）。
 *
 * <p>{@code dimensionScores} / {@code careItems} 的访问器做不可变快照（同 {@code LoginResponse.roles}
 * 范式），避免内部列表被外部改写 （SpotBugs EI_EXPOSE_REP/REP2）。
 */
@Data
public class FamilyReportDetailView {

    private Long reportId;

    private String reportNo;

    private Long elderId;

    private String gradeCode;

    /** 等级名称（优先终判 {@code final_level_name}，缺失回退初判名）。 */
    private String levelName;

    private BigDecimal totalScore;

    private LocalDateTime publishedAt;

    private List<DimensionScoreRow> dimensionScores;

    /** 照护条目（空态为空列表）。 */
    private List<String> careItems;

    /** 读取返回不可变视图（未赋值时为空集合）。 */
    public List<DimensionScoreRow> getDimensionScores() {
        return dimensionScores == null ? List.of() : List.copyOf(dimensionScores);
    }

    /** 写入时快照。 */
    public void setDimensionScores(List<DimensionScoreRow> dimensionScores) {
        this.dimensionScores = dimensionScores == null ? List.of() : List.copyOf(dimensionScores);
    }

    /** 读取返回不可变视图（未赋值时为空集合）。 */
    public List<String> getCareItems() {
        return careItems == null ? List.of() : List.copyOf(careItems);
    }

    /** 写入时快照。 */
    public void setCareItems(List<String> careItems) {
        this.careItems = careItems == null ? List.of() : List.copyOf(careItems);
    }
}
