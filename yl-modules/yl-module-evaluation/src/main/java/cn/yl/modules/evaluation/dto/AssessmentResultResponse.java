package cn.yl.modules.evaluation.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 评估单结果响应（落库后的判定结果）。
 *
 * <p>同时携带初判等级与终判等级及变化原因，供报告侧直接展示（PRD §1.4）；{@code ruleVersion} / {@code ruleSha} 为事后复算锚点（C3）。
 */
@Data
public class AssessmentResultResponse {

    private Long orderId;
    private String orderNo;
    private Long taskId;
    private String status;

    private BigDecimal totalScore;

    /** 落库等级码：GRADE_INTACT / GRADE_MILD / GRADE_MODERATE / GRADE_SEVERE / GRADE_TOTAL。 */
    private String gradeCode;

    /** 引擎初判等级码（L0~L4）。 */
    private String preliminaryLevelCode;

    private String preliminaryLevelName;

    /** 引擎终判等级码（L0~L4）。 */
    private String levelCode;

    private String levelName;

    /** 是否触发等级上调。 */
    private Integer gradeUpgraded;

    private String levelChangeReason;
    private Integer refuseCount;
    private String ruleVersion;
    private String ruleSha;

    /** 维度得分明细（雷达图）；分值来自服务端引擎，四端不本地算分。 */
    private List<DimScoreItem> dimScores = new ArrayList<>();

    public List<DimScoreItem> getDimScores() {
        return List.copyOf(dimScores);
    }

    public void setDimScores(List<DimScoreItem> dimScores) {
        this.dimScores = dimScores == null ? new ArrayList<>() : new ArrayList<>(dimScores);
    }
}
