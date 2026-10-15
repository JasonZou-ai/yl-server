package cn.yl.modules.evaluation.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 「本人报告列表」行（rR1a6L B5-后端：{@code GET /api/v1/evaluation/reports}）。
 *
 * <p><b>最小字段集</b>（契约 {@code ReportListItem}）：只承载「拿到清单 → 再按 {@code reportNo} 下载 PDF / 按 {@code
 * orderId} 查详情」所需的最小信息。<b>刻意不含</b>：
 *
 * <ul>
 *   <li>姓名 / 身份证 / 手机号等敏感字段明文（要明文须走 {@code /security/reveal} 二次验证 + 留痕）；
 *   <li><b>维度分</b>：维度分只走报告详情聚合（{@code EvalReportResponse#dimScores}），列表不重复下发 —— 避免把「最小清单」做成第二份详情面。
 * </ul>
 */
@Data
public class ReportListItem {

    /** 报告编号（读取/分发轴）。 */
    private String reportNo;

    private Long elderId;

    /** 发布时间（列表按此倒序）。 */
    private LocalDateTime publishedAt;

    /** 终判等级码（GRADE_*）。 */
    private String gradeCode;

    /** 终判等级码 L0~L4。 */
    private String finalLevelCode;

    private BigDecimal totalScore;
}
