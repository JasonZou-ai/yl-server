package cn.yl.infrastructure.secondverify;

import cn.yl.common.security.SecondVerifyStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 二次验证凭据存储的 Redis 实现（B2 / ri91pT）。
 *
 * <p>键结构：{@code yl:2fa:{token}} → userId，TTL 5 分钟。消费使用 {@code GETDEL}（{@code getAndDelete}）保证
 * 原子「一次有效」，杜绝凭据重放。
 */
@Component
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification =
                                "StringRedisTemplate 是 Spring 容器管理的共享线程安全客户端，构造器注入是标准 DI 用法"))
public class RedisSecondVerifyStore implements SecondVerifyStore {

    private static final String KEY_PREFIX = "yl:2fa:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public void issue(String token, long userId, long ttlSeconds) {
        redisTemplate
                .opsForValue()
                .set(KEY_PREFIX + token, String.valueOf(userId), Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public boolean consume(String token, long userId) {
        if (token == null || token.isBlank()) {
            return false;
        }
        String value = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + token);
        return value != null && value.equals(String.valueOf(userId));
    }
}
