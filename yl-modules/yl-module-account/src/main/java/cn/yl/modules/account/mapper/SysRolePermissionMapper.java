package cn.yl.modules.account.mapper;

import cn.yl.modules.account.domain.entity.SysRolePermission;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 角色-权限关联 Mapper（sys_role_permission）。 */
@Mapper
public interface SysRolePermissionMapper extends BaseMapper<SysRolePermission> {}
