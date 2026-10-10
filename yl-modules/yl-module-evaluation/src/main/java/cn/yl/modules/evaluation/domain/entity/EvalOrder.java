package cn.yl.modules.evaluation.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 评估单（eval_order，PRD §4.4 六状态机）。
 *
 * <p>状态：DRAFT → SUBMITTED → PUBLISHED / REJECTED → VOID / ARCHIVED。
 *
 * <p>{@code ruleVersionId} 绑定本次判定所用国标规则版本，是事后复算的锚点（C3 据此回填报告）。 {@code gradeCode} 取值 GRADE_INTACT /
 * GRADE_MILD / GRADE_MODERATE / GRADE_SEVERE / GRADE_TOTAL，由引擎等级码 L0~L4 翻译得到。
 */
@Data
@TableName("eval_order")
public class EvalOrder {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String orderNo;
    private Long taskId;
    private Long elderId;
    private Long orgId;
    private Long assessorId;

    /** 复核人，必须 ≠ 录入人（互斥）。 */
    private Long reviewerId;

    private Long ruleVersionId;

    /** 总分（0~90，分越高能力越好；满分取自规则集 fullScore）。 */
    private BigDecimal totalScore;

    private String gradeCode;

    /** 是否触发等级上调规则（痴呆 / 精神行为障碍 / 照护风险事件）。 */
    private Integer gradeUpgraded;

    private String upgradeReason;

    /** 初判等级码（引擎 preliminaryLevelCode，L0~L4）。与 {@code gradeCode}（终判）共同构成 PRD §1.4 要求的等级变化展示。 */
    private String preliminaryGradeCode;

    /** 初判等级名称。 */
    private String preliminaryLevelName;

    /** 终判等级码（引擎 levelCode，L0~L4）。 */
    private String finalLevelCode;

    /** 终判等级名称。 */
    private String finalLevelName;

    /** 等级变化原因（初判 → 终判，特殊规则触发依据）。 */
    private String gradeChangeReason;

    /** 判定所用规则集内容指纹 SHA-256，事后复算锚点（C3）。 */
    private String ruleSha;

    private String status;

    /** 拒绝回答项数（不计分，触发人工复核）。 */
    private Integer refuseCount;

    /** 重复评估（30 天内）强制填写的变更原因。 */
    private String changeReason;

    private LocalDateTime submitAt;
    private LocalDateTime reviewAt;
    private String reviewOpinion;
    private LocalDateTime publishedAt;

    /** 乐观锁版本号（离线冲突检测）。 */
    private Integer rowVersion;

    /** 保留至（评估记录 5 年，ER-03）。 */
    private LocalDateTime retainUntil;

    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
