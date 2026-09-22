package cn.yl.modules.account.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 第三方账号绑定（sys_user_third_party，ER-14）。平台身份 ↔ 本服务账号的绑定关系。
 *
 * <p>只存 HMAC-SHA256 摘要，不存 openid / unionid / 手机号明文（PM 硬否决明文方案，对齐 ER-11 与既有隐私承诺）。
 *
 * <p>{@code active_uk} 是 {@code GENERATED ALWAYS AS ... STORED} 的生成列，由数据库维护，实体侧<b>不映射</b>，避免插入时被
 * 显式赋值而报错。
 *
 * <p>{@code retain_until} ＝ 账号注销时间 + 30 天，即本行的<b>物理删除时点</b>（PM 裁决 2026-09-21）。到期后由 {@code
 * ThirdPartyRetentionCleanupTask} 物理清除，不再依赖软删标记。
 */
@Data
@TableName("sys_user_third_party")
public class SysUserThirdParty {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** sys_user.id。 */
    private Long userId;

    /** 平台：WECHAT / DOUYIN / APPLE。 */
    private String platform;

    /** 平台用户标识 HMAC-SHA256（等值检索，不存明文）。 */
    private String openIdHash;

    /** 开放平台账号标识 HMAC-SHA256（可空）。 */
    private String unionIdHash;

    /** 绑定时手机号 HMAC-SHA256（与 sys_user.phone_hash 同算法）。 */
    private String phoneHash;

    private LocalDateTime boundAt;
    private LocalDateTime lastLoginAt;
    private Integer deleted;

    /** 物理删除时点；为 {@code null} 表示未进入注销保留期，永不清理。 */
    private LocalDateTime retainUntil;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
