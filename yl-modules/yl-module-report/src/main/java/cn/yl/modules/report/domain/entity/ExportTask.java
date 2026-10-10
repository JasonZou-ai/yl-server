package cn.yl.modules.report.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 导出任务（{@code export_task}）。
 *
 * <p><b>无 {@code deleted} 列</b>（本表无软删）→ 实体<b>不映射</b> {@code deleted}，查询不追加 {@code deleted = 0}。
 *
 * <p>{@code orgId} 为 D-3 裁决 1-A 新增的可空机构锚列：与既有 {@code operatorId}（SELF_COLUMN）并列共存（同 {@code
 * eval_task} 范式）。{@code expire_at} <b>只写值</b>（清理语义整体归 P2-7）。
 */
@Data
@TableName("export_task")
public class ExportTask {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 导出任务号（唯一 {@code uk_export_no}）。 */
    private String taskNo;

    /** 导出类型（D-3 仅 EVAL_LIST）。 */
    private String exportType;

    /** 导出格式（D-3 仅 CSV）。 */
    private String format;

    /** 查询条件快照（JSON）。 */
    private String queryParams;

    /** 脱敏级别（1-标准）。 */
    private Integer maskLevel;

    /** 导出条数。 */
    private Integer rowCount;

    /** 0-排队 1-生成中 2-完成 3-失败 4-已过期。 */
    private Integer status;

    /** 对象键（{@code export/<taskNo>.csv}）。 */
    private String fileCosKey;

    /** 发起人（取自登录态）。 */
    private Long operatorId;

    /** 归属机构（D-3 裁决 1-A，可空）。 */
    private Long orgId;

    private LocalDateTime startedAt;
    private LocalDateTime finishedAt;

    /** 文件过期时间（只写值，清理排 P2-7）。 */
    private LocalDateTime expireAt;

    private LocalDateTime createdAt;
}
