package cn.yl.modules.account.mapper;

import cn.yl.modules.account.domain.entity.SysUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.Optional;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 用户账号 Mapper（sys_user）。 */
@Mapper
public interface SysUserMapper extends BaseMapper<SysUser> {

    /** 按登录名查询未删除账号。 */
    @Select("SELECT * FROM sys_user WHERE username = #{username} AND deleted = 0")
    Optional<SysUser> selectByUsername(@Param("username") String username);
}
