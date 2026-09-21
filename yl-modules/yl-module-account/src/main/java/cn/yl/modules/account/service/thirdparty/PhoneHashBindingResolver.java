package cn.yl.modules.account.service.thirdparty;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.common.security.SensitiveFieldCodec;
import cn.yl.modules.account.domain.entity.SysUser;
import cn.yl.modules.account.domain.thirdparty.ThirdPartyBindingResolver;
import cn.yl.modules.account.domain.thirdparty.ThirdPartyIdentity;
import cn.yl.modules.account.mapper.SysUserMapper;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 手机号绑定解析（B2 / rpW1xZ 本期实现）。
 *
 * <p>平台侧手机号 → HMAC-SHA256 摘要（与 {@code sys_user.phone_hash} 同算法、同密钥）→ 命中即完成绑定。
 * 明文手机号**不落库、不入日志**，只在内存中转为摘要后查询（对齐《脱敏与埋点合规方案》字段分级）。
 *
 * <p>未配置摘要密钥时（{@code yl.security.sensitive-field-hash-key}）**明确拒绝**，而非放行或误报「未绑定」。
 */
@Component
@RequiredArgsConstructor
public class PhoneHashBindingResolver implements ThirdPartyBindingResolver {

    private final SysUserMapper userMapper;
    private final ObjectProvider<SensitiveFieldCodec> codecProvider;

    @Override
    public Optional<String> resolveUsername(ThirdPartyIdentity identity) {
        String phone = identity.phone();
        if (phone == null || phone.isBlank()) {
            return Optional.empty();
        }
        SensitiveFieldCodec codec = codecProvider.getIfAvailable();
        if (codec == null) {
            throw new BizException(
                    ErrorCode.LOGIN_FAILED,
                    "未配置敏感字段摘要密钥（yl.security.sensitive-field-hash-key），暂无法按手机号完成平台账号绑定");
        }
        return userMapper
                .selectByPhoneHash(codec.hashForSearch(phone.trim()))
                .map(SysUser::getUsername);
    }
}
