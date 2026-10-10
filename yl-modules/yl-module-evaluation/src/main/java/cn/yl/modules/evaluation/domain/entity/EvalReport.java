package cn.yl.modules.evaluation.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 评估报告（eval_report，ER-03 保留期）。
 *
 * <p>C3（PRD §1.4）：报告须<b>同时展示初判等级 + 终判等级 + 等级变化原因</b>，并绑定判定所用规则版本 {@code ruleVersionId} 与内容指纹 {@code
 * ruleSha}，以支持事后复算与审计追溯。
 *
 * <p>{@code active_uk} 为 {@code GENERATED ALWAYS AS ... STORED}
 * 生成列，由数据库维护，实体侧<b>不映射</b>，避免插入时被显式赋值报错。
 */
@Data
@TableName("eval_report")
public class EvalReport {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 报告编号（二维码验真用）。 */
    private String reportNo;

    private Long orderId;
    private Long elderId;
    private Long orgId;

    /** 终判等级码。 */
    private String gradeCode;

    private BigDecimal totalScore;

    /** 判定所用国标规则版本（gb_rule_version.id）。 */
    private Long ruleVersionId;

    /** 判定所用规则集内容指纹 SHA-256。 */
    private String ruleSha;

    /** 初判等级码（L0~L4）。 */
    private String preliminaryGradeCode;

    /** 初判等级名称。 */
    private String preliminaryLevelName;

    /** 终判等级码（L0~L4）。 */
    private String finalLevelCode;

    /** 终判等级名称。 */
    private String finalLevelName;

    /** 等级变化原因（初判 → 终判）。 */
    private String levelChangeReason;

    private String pdfCosKey;
    private String qrContent;

    /** 报告内容摘要（防篡改验真）。 */
    private String verifyHash;

    /** 0-生成中 1-有效 2-已作废。 */
    private Integer status;

    private LocalDateTime generatedAt;
    private LocalDateTime publishedAt;

    /** 保留至（报告 5 年，ER-03）。 */
    private LocalDateTime retainUntil;

    private Integer deleted;
    private LocalDateTime createdAt;
}
