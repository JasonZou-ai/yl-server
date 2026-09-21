package cn.yl.modules.account.mapper;

import cn.yl.modules.account.domain.entity.SysUserRole;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Mapper;

/** 用户-角色关联 Mapper（sys_user_role）。 */
@Mapper
public interface SysUserRoleMapper extends BaseMapper<SysUserRole> {}
