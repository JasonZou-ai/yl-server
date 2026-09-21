package cn.yl.modules.account.mapper;

import cn.yl.modules.account.domain.entity.SysPermission;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 权限点 Mapper（sys_permission）。 */
@Mapper
public interface SysPermissionMapper extends BaseMapper<SysPermission> {

    /**
     * 查询用户聚合后的权限码集合（去重）。经 用户→角色→权限 三级关联，仅返回未删除的角色与权限。
     *
     * <p>含 need_second_verify=1 的敏感权限点（导出/作废/解绑），是否授予由调用方结合二次验证流程判定。
     */
    @Select(
            "SELECT DISTINCT p.perm_code FROM sys_permission p "
                    + "JOIN sys_role_permission rp ON rp.perm_id = p.id "
                    + "JOIN sys_user_role ur ON ur.role_id = rp.role_id "
                    + "WHERE ur.user_id = #{userId} AND p.deleted = 0")
    List<String> selectPermCodesByUserId(@Param("userId") Long userId);
}
