package cn.yl.modules.account.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 用户账号（sys_user）。
 *
 * <p>敏感字段（手机号、真实姓名）一律密文/摘要落库，禁止明文（合规约束）。{@code deleted} 由 MyBatis-Plus
 * 全局逻辑删除配置（field=deleted）自动处理，无需显式 {@code @TableLogic}。
 */
@Data
@TableName("sys_user")
public class SysUser {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String username;
    private String phoneEnc;
    private String phoneHash;
    private String passwordHash;
    private String realNameEnc;
    private String avatarCosKey;
    private Integer status;
    private LocalDateTime pwdUpdatedAt;
    private LocalDateTime lastLoginAt;
    private Integer deleted;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
