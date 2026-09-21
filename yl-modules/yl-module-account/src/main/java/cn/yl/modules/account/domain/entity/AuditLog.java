package cn.yl.modules.account.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 审计日志（audit_log）。敏感操作留痕，保留期 3 年（ER-03）。
 *
 * <p>无 {@code deleted} 列：审计日志只增不改不删，由 {@code retain_until} 到期清理，故不参与全局逻辑删除。 {@code detail}
 * 为已脱敏的操作上下文 JSON。
 */
@Data
@TableName("audit_log")
public class AuditLog {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String action;
    private String bizType;
    private Long bizId;
    private Long operatorId;
    private String operatorName;
    private String ip;
    private String ua;
    private Integer sensitive;
    private Integer secondVerify;
    private String detail;
    private LocalDateTime retainUntil;
    private LocalDateTime createdAt;
}
