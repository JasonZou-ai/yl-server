package cn.yl.modules.account.security;

import cn.yl.common.security.SecondVerifyStore;
import java.security.SecureRandom;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 二次验证凭据守卫（四道闸·第四闸，设计 §3/§5）。
 *
 * <p>敏感操作前置：先经 {@link SecondVerifyVerifier} 校验验证方式（SCAN/PHONE/FACE），通过后由本类签发一次性凭据；业务 请求携带 {@value
 * #HEADER} 头，本类原子消费（一次有效）校验。凭据 TTL {@value #TTL_SECONDS} 秒。
 */
@Component
@RequiredArgsConstructor
public class SecondVerifyGuard {

    /** 二次验证一次性凭据请求头。 */
    public static final String HEADER = "X-Second-Verify-Token";

    /** 凭据有效期（秒）。 */
    public static final long TTL_SECONDS = 300;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecondVerifyStore store;

    /** 签发一次性凭据（192 位随机，十六进制）。 */
    public String issue(long userId) {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        String token = HexFormat.of().formatHex(bytes);
        store.issue(token, userId, TTL_SECONDS);
        return token;
    }

    /** 原子校验并消费一次性凭据（不存在/已用/过期/归属不匹配均返回 false）。 */
    public boolean verify(String token, long userId) {
        return token != null && !token.isBlank() && store.consume(token, userId);
    }
}
