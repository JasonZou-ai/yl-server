package cn.yl.modules.evaluation.dto;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 评估单流转日志响应（eval_review_log，审计链）。
 *
 * <p>每次状态流转（SUBMIT / PUBLISH / REJECT / VOID / ARCHIVE）写一行，含操作人与意见，构成可追溯审计链。
 */
@Data
public class ReviewLogResponse {

    private Long id;
    private Long orderId;
    private String action;
    private String fromStatus;
    private String toStatus;
    private Long operatorId;
    private String opinion;
    private LocalDateTime createdAt;
}
