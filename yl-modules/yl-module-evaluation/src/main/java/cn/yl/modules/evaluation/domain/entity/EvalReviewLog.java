package cn.yl.modules.evaluation.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 评估单流转日志（eval_review_log，状态机审计）。
 *
 * <p>action：SUBMIT / PUBLISH / REJECT / VOID / ARCHIVE。每次状态流转写一行，含操作人与意见，构成可追溯审计链。
 *
 * <p>本表无 {@code deleted} 列（日志类不软删），故不映射软删字段。
 */
@Data
@TableName("eval_review_log")
public class EvalReviewLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long orderId;
    private String action;
    private String fromStatus;
    private String toStatus;
    private Long operatorId;
    private String opinion;
    private LocalDateTime createdAt;
}
