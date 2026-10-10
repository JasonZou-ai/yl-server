package cn.yl.modules.account.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
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

    /**
     * 是否敏感操作（1-敏感）。
     *
     * <p><b>必须反引号包裹</b>：{@code SENSITIVE} 在 MySQL 8 属<b>保留字</b>（{@code information_schema.KEYWORDS}
     * 的 {@code RESERVED=1} 集合，共 262 个之一）。不加反引号时 MyBatis-Plus 生成的 {@code INSERT ... (sensitive,
     * ...)} 会被服务器判为语法错误， 结果是<b>审计留痕整体写入失败</b>——不报编译错、不在单测暴露（Mock 掉 Mapper 即绕过），只在真实库上以 0 行留痕静默存在（实测
     * {@code audit_log} 曾长期 0 行，见 CR-M2-003）。
     */
    @TableField("`sensitive`")
    private Integer sensitive;

    private Integer secondVerify;
    private String detail;
    private LocalDateTime retainUntil;
    private LocalDateTime createdAt;
}
