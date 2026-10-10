package cn.yl.modules.account.mapper;

import cn.yl.modules.account.domain.entity.SysUser;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
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

    /**
     * 列出未删除的 {@code *_dev} 联调账号登录名（启动守卫用，见 {@code DevCredentialStartupGuard}）。
     *
     * <p>{@code \_} 为 LIKE 转义，确保只匹配以下划线紧接 {@code dev} 结尾的登录名（否则 {@code _} 会通配任意单字符）。
     */
    @Select(
            "SELECT username FROM sys_user WHERE deleted = 0 AND username LIKE '%\\_dev' ORDER BY username")
    List<String> listDevUsernames();

    /**
     * 统计密码散列等于给定值的未删除账号数（启动守卫的「已知泄露散列」金丝雀）。
     *
     * <p>注意与 {@code scripts/verify-schema.sh} 的 dev 账号断言口径不同：那里只校验<b>散列长度</b>
     * （为了让口令可轮换），此处校验<b>散列具体值</b> —— 二者互补，缺一不可。
     */
    @Select("SELECT COUNT(*) FROM sys_user WHERE deleted = 0 AND password_hash = #{passwordHash}")
    long countByPasswordHash(@Param("passwordHash") String passwordHash);
}
