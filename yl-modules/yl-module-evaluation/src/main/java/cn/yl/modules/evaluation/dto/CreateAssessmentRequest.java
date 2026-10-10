package cn.yl.modules.evaluation.dto;

import lombok.Data;

/**
 * 新建评估请求（评估任务 + 评估单一步创建）。
 *
 * <p>elderId / orgId / assessorId 为必填；assessorId 为空表示待接单。
 */
@Data
public class CreateAssessmentRequest {

    private Long elderId;
    private Long orgId;
    private Long assessorId;

    /** 评估场景：居家 / 机构 / 社区。 */
    private String scene;

    private Integer priority;

    /** 截止时间（催办口径）。 */
    private String dueAt;

    /** 重复评估（30 天内）强制填写的变更原因。 */
    private String changeReason;
}
