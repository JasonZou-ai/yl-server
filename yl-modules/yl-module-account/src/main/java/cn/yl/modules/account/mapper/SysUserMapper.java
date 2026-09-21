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

    /**
     * 按手机号摘要查询未删除账号（平台授权渠道的绑定解析入口）。
     *
     * <p>入参必须是 HMAC-SHA256 摘要，而非明文手机号——明文不落库也不参与查询条件。
     *
     * @param phoneHash {@code SensitiveFieldCodec#hashForSearch} 产出的摘要
     */
    @Select("SELECT * FROM sys_user WHERE phone_hash = #{phoneHash} AND deleted = 0 LIMIT 1")
    Optional<SysUser> selectByPhoneHash(@Param("phoneHash") String phoneHash);
}
