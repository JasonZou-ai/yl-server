package cn.yl.infrastructure.ratelimit;

import cn.yl.common.ratelimit.RateLimiter;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 限流的 Redis 实现（B1-2 / 子任务 rPX9p2）。
 *
 * <p>固定窗口计数：{@code INCR} + 首次设置 {@code EXPIRE}（秒级窗口）。 相比令牌桶实现更简单，且在「防刷 / 防误操作」场景已足够；高精度场景可在 M3
 * 压测后替换为 Lua 令牌桶。
 *
 * <p>键结构：{@code yl:rl:{dimension}:{窗口起点}}，天然按窗口自动分桶。
 */
@Component
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification =
                                "StringRedisTemplate 是 Spring 容器管理的共享线程安全客户端，"
                                        + "由构造器注入是标准 DI 用法，不构成外部可变状态泄露"))
public class RedisRateLimiter implements RateLimiter {

    private static final String KEY_PREFIX = "yl:rl:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean tryAcquire(String dimension, int limit, int windowSeconds) {
        if (limit <= 0) {
            return true;
        }
        long windowStart =
                System.currentTimeMillis() / (windowSeconds * 1000L) * (windowSeconds * 1000L);
        String key = KEY_PREFIX + dimension + ":" + windowStart;

        Long count = redisTemplate.opsForValue().increment(key);
        if (count == null) {
            return true;
        }
        if (count == 1L) {
            // 首个请求设置过期，窗口结束后自动清理，避免键无限增长
            redisTemplate.expire(key, Duration.ofSeconds(windowSeconds));
        }
        return count <= limit;
    }
}
