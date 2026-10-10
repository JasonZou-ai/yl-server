package cn.yl.modules.evaluation.dto;

import lombok.Data;

/**
 * 提交评估请求（含特殊标志，驱动等级上调规则）。
 *
 * <p>对应 GB/T 42195-2022 §7.2 等级上调说明：昏迷直判、确诊痴呆(F00~F03)、其他精神和行为障碍(F04~F99)、 近 30 天 ≥2 次照护风险事件。
 */
@Data
public class SubmitAssessmentRequest {

    /** 昏迷：直判能力完全丧失（等级 4）。 */
    private boolean coma;

    /** 确诊痴呆（ICD-10 F00~F03）。 */
    private boolean dementia;

    /** 精神科专科医生诊断的其他精神和行为障碍（ICD-10 F04~F99）。 */
    private boolean mentalDisorder;

    /** 近 30 天内照护风险事件次数（≥2 触发上调）。 */
    private int riskEventCount;

    /** 重复评估（30 天内）强制填写的变更原因。 */
    private String changeReason;

    /** 操作人（留痕用）；为空时回退评估单录入人。 */
    private Long operatorId;
}
