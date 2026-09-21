package cn.yl.infrastructure.token;

import cn.yl.common.security.RefreshTokenStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 刷新令牌存储的 Redis 实现（B2 / rpW1xZ）。
 *
 * <p>键结构：{@code yl:rt:{refreshToken}} → userId。TTL 与令牌有效期一致（默认 14d），到期自动清理；注销/吊销即删除键， 使 refresh
 * 不可再换发 access（双令牌可吊销的关键）。
 */
@Component
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification =
                                "StringRedisTemplate 是 Spring 容器管理的共享线程安全客户端，构造器注入是标准 DI 用法"))
public class RedisRefreshTokenStore implements RefreshTokenStore {

    private static final String KEY_PREFIX = "yl:rt:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void save(String token, long userId, long ttlSeconds) {
        redisTemplate
                .opsForValue()
                .set(KEY_PREFIX + token, String.valueOf(userId), Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public Long findUserId(String token) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String value = redisTemplate.opsForValue().get(KEY_PREFIX + token);
        if (value == null) {
            return null;
        }
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    @Override
    public void revoke(String token) {
        if (token != null && !token.isBlank()) {
            redisTemplate.delete(KEY_PREFIX + token);
        }
    }
}
