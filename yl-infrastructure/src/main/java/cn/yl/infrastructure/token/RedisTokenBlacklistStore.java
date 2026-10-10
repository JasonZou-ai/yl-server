package cn.yl.infrastructure.token;

import cn.yl.common.security.TokenBlacklistStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 访问令牌黑名单的 Redis 实现（D-4 / `authLogout`）。
 *
 * <p>键结构：{@code yl:bl:at:<sha256Hex(token)>} → userId；TTL 取令牌剩余有效期，到期自动清理（无需显式回收）。<b>不存原令牌</b>： 即便
 * Redis 数据泄漏，攻击者拿到的是不可逆散列，无法据此伪造可用令牌。
 *
 * <p>散列在实现层完成，{@link #add} 与 {@link #isBlacklisted} 共用同一键函数，保证写入与命中的键空间一致。
 */
@Component
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification =
                                "StringRedisTemplate 是 Spring 容器管理的共享线程安全客户端，构造器注入是标准 DI 用法"))
public class RedisTokenBlacklistStore implements TokenBlacklistStore {

    /** 访问令牌黑名单键前缀（区别于 refresh 的 {@code yl:rt:}）。 */
    private static final String KEY_PREFIX = "yl:bl:at:";

    private static final String HASH_ALGORITHM = "SHA-256";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void add(String token, long userId, long ttlSeconds) {
        if (token == null || token.isBlank() || ttlSeconds <= 0) {
            // 空令牌或已过期令牌无需登记（已过期令牌本就无法通过验签）
            return;
        }
        redisTemplate
                .opsForValue()
                .set(
                        KEY_PREFIX + sha256Hex(token),
                        String.valueOf(userId),
                        Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public boolean isBlacklisted(String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + sha256Hex(token)));
    }

    /** 计算 {@code sha256} 十六进制摘要（定长 64 字符，无碰撞面）。 */
    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance(HASH_ALGORITHM);
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JVM 必备算法，理论上不可达；不可用时无法安全登记黑名单（fail-closed 由调用方 401 兜底）
            throw new IllegalStateException("JVM 不支持 SHA-256，无法登记访问令牌黑名单", e);
        }
    }
}
