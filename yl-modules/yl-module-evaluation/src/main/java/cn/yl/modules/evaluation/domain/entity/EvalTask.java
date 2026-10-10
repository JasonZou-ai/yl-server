package cn.yl.modules.evaluation.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 评估任务（eval_task）。评估员接单前的工作项，一个任务下可开具一张评估单。
 *
 * <p>status：0-待接单 1-进行中 2-已完成 3-已取消。
 */
@Data
@TableName("eval_task")
public class EvalTask {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String taskNo;
    private Long elderId;
    private Long orgId;
    private Long assessorId;

    /** 评估场景：居家 / 机构 / 社区。 */
    private String scene;

    private Integer priority;
    private LocalDateTime dueAt;

    /** 0-待接单 1-进行中 2-已完成 3-已取消。 */
    private Integer status;

    private Integer deleted;
    private Long createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
