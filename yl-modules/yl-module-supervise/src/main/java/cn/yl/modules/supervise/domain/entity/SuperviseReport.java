package cn.yl.modules.supervise.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 监管上报记录（{@code supervise_report}，ER-03 保留期）。
 *
 * <p><b>无 {@code deleted} 列</b> → 实体<b>不映射</b> {@code deleted}，查询不追加 {@code deleted = 0}。
 *
 * <p>{@code retainUntil} 由 B10 写入口径写入（保留 5 年）；到期归档由 {@code SuperviseReportArchiveTask}（D-5 裁决
 * D5-D1）接入 {@code RetentionChainScheduler} 统一调度，置 {@link #archivedAt} 做<b>只读封存</b>、不物理删除。 {@code
 * tsToken}（可信时间戳）无可信服务，保留 NULL。
 */
@Data
@TableName("supervise_report")
public class SuperviseReport {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 上报月份（{@code YYYY-MM}）。 */
    private String reportMonth;

    /** 归属机构（NOT NULL）。 */
    private Long orgId;

    /** 上报报文（JSON，服务端聚合生成）。 */
    private String payload;

    /** 可信时间戳（D-3 不写入）。 */
    private String tsToken;

    /** 0-待上报 1-成功 2-失败 3-重试中。 */
    private Integer status;

    private Integer retryCount;
    private String lastError;

    /** 上报幂等键（唯一 {@code uk_idem}，服务端生成）。 */
    private String idempotentKey;

    private LocalDateTime sentAt;

    /** 保留至（监管报表 5 年，ER-03；只写值）。 */
    private LocalDateTime retainUntil;

    /**
     * 归档时间（ER-03 到期只读封存，D-5 裁决 D5-D1）。
     *
     * <p>{@code NULL} = 未归档；由 {@code SuperviseReportArchiveTask} 在 {@code retain_until} 到期后手写 SQL
     * 置为 {@code NOW()}，**只读封存、行保留**（不物理删除），且**不改动 {@link #status}（上报状态）**。
     */
    private LocalDateTime archivedAt;

    private LocalDateTime createdAt;
}
