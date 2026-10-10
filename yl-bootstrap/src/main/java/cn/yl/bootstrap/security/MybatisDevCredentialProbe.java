package cn.yl.bootstrap.security;

import cn.yl.modules.account.mapper.SysUserMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** {@link DevCredentialProbe} 的 MyBatis 实现（只读两问，见 {@link SysUserMapper}）。 */
@Component
@RequiredArgsConstructor
public class MybatisDevCredentialProbe implements DevCredentialProbe {

    private final SysUserMapper sysUserMapper;

    @Override
    public List<String> listDevUsernames() {
        List<String> usernames = sysUserMapper.listDevUsernames();
        return usernames == null ? List.of() : List.copyOf(usernames);
    }

    @Override
    public long countByPasswordHash(String passwordHash) {
        return sysUserMapper.countByPasswordHash(passwordHash);
    }
}
