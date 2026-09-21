package cn.yl.modules.account.mapper;

import cn.yl.modules.account.domain.entity.SysRole;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 角色 Mapper（sys_role）。 */
@Mapper
public interface SysRoleMapper extends BaseMapper<SysRole> {

    /** 查询用户拥有的角色实体（未删除）。 */
    @Select(
            "SELECT r.* FROM sys_role r "
                    + "JOIN sys_user_role ur ON ur.role_id = r.id AND ur.deleted = 0 "
                    + "WHERE ur.user_id = #{userId} AND r.deleted = 0")
    List<SysRole> selectByUserId(@Param("userId") Long userId);

    /** 查询用户拥有的角色码集合（未删除）。 */
    @Select(
            "SELECT r.role_code FROM sys_role r "
                    + "JOIN sys_user_role ur ON ur.role_id = r.id AND ur.deleted = 0 "
                    + "WHERE ur.user_id = #{userId} AND r.deleted = 0")
    List<String> selectRoleCodesByUserId(@Param("userId") Long userId);
}
