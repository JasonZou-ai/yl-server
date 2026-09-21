package cn.yl.modules.account.domain.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/**
 * 权限点（sys_permission）。PRD §2.2 权限矩阵九行展开为 14 个原子权限 + 2 个敏感操作权限点 = 16。
 *
 * <p>need_second_verify=1 表示该权限点对应敏感操作（导出/作废/解绑），须二次验证并留痕，对应 audit_log。
 */
@Data
@TableName("sys_permission")
public class SysPermission {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String permCode;
    private String permName;
    private String module;
    private Integer needSecondVerify;
    private Integer deleted;
}
