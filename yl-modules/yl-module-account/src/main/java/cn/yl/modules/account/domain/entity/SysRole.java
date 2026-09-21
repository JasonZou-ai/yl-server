package cn.yl.modules.account.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;
import lombok.Data;

/**
 * 角色（sys_role）。五角色（ELDER/ASSESSOR/FAMILY/ORG_ADMIN/SUPERVISOR）不得增删或简并，对应 PRD §2.1。
 *
 * <p>data_scope 取值域：1-本人 / 2-本机构 / 3-全量 / 4-只读全局（见 {@code DataScope}），本期五角色不占用 3-全量。
 */
@Data
@TableName("sys_role")
public class SysRole {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String roleCode;
    private String roleName;
    private Integer dataScope;
    private Integer deleted;
    private LocalDateTime createdAt;
}
