package cn.yl.modules.evaluation.dto;

import java.time.LocalDateTime;
import lombok.Data;

/** B6 评估任务列表行（不含任何个人信息敏感字段）。 */
@Data
public class EvalTaskView {

    private Long id;
    private String taskNo;
    private Long elderId;
    private Long orgId;
    private Long assessorId;
    private String scene;
    private Integer status;
    private LocalDateTime dueAt;
    private LocalDateTime createdAt;
}
