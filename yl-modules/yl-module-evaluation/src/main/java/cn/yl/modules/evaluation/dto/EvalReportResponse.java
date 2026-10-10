package cn.yl.modules.evaluation.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 评估报告响应（PRD §1.4 验收口径）。
 *
 * <p>同时给出<b>初判等级</b>、<b>终判等级</b>与<b>等级变化原因</b>，并绑定判定所用规则版本与内容指纹， 支持事后复算（用同一 ruleVersion + ruleSha
 * 的规则集重算可得到相同结果）。
 *
 * <p>{@code dimScores} 为雷达图数据源；{@code pdfCosKey} / {@code qrContent} 为 PDF 与验真二维码（报告发布后可下载）。
 */
@Data
public class EvalReportResponse {

    private Long reportId;
    private String reportNo;
    private Long orderId;
    private Long elderId;
    private String status;

    private BigDecimal totalScore;

    /** 终判等级码（GRADE_*）。 */
    private String gradeCode;

    /** 初判等级码（L0~L4）。 */
    private String preliminaryGradeCode;

    private String preliminaryLevelName;

    /** 终判等级码（L0~L4）。 */
    private String finalLevelCode;

    private String finalLevelName;

    /** 等级变化原因（初判 → 终判）。 */
    private String levelChangeReason;

    /** 判定所用规则版本号（如 1.0.0）。 */
    private String ruleVersion;

    /** 判定所用规则集内容指纹 SHA-256。 */
    private String ruleSha;

    /** 报告内容摘要（防篡改验真）。 */
    private String verifyHash;

    /** 评估单状态（DRAFT/SUBMITTED/PUBLISHED/REJECTED/VOID），决定四端是否展示等级卡片。 */
    private String orderStatus;

    /** 报告发布时间（PRD P02：仅「已发布」展示等级卡片）。 */
    private LocalDateTime publishedAt;

    /** 4 维度得分明细（雷达图数据源，服务端算分）。 */
    private List<DimScoreItem> dimScores = new ArrayList<>();

    /** PDF 对象键（生成后回填）。 */
    private String pdfCosKey;

    /** 验真二维码内容。 */
    private String qrContent;

    public List<DimScoreItem> getDimScores() {
        return List.copyOf(dimScores);
    }

    public void setDimScores(List<DimScoreItem> dimScores) {
        this.dimScores = dimScores == null ? new ArrayList<>() : new ArrayList<>(dimScores);
    }
}
