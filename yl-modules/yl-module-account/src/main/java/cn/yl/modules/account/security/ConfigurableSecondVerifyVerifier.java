package cn.yl.modules.account.security;

import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 可配置二次验证校验器（MVP，B2 / ri91pT）。
 *
 * <p>支持 SCAN/PHONE/FACE 三种方式。联调期以 {@code yl.security.second-verify.dev-code} 配置口令比对；<b>未配置时一律
 * 拒绝</b>（安全默认），避免开箱即可被绕过。生产须替换为真实的短信/人脸渠道实现。
 */
@Component
public class ConfigurableSecondVerifyVerifier implements SecondVerifyVerifier {

    private static final Set<String> SUPPORTED = Set.of("SCAN", "PHONE", "FACE");

    private final String devCode;

    public ConfigurableSecondVerifyVerifier(
            @Value("${yl.security.second-verify.dev-code:}") String devCode) {
        this.devCode = devCode;
    }

    @Override
    public boolean verify(long userId, String method, String credential) {
        if (method == null || !SUPPORTED.contains(method.trim().toUpperCase(Locale.ROOT))) {
            return false;
        }
        return devCode != null && !devCode.isBlank() && devCode.equals(credential);
    }
}
