package cn.yl.infrastructure.ratelimit;

import cn.yl.common.ratelimit.SlidingWindowCounter;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 滑动窗口计数器的 Redis 实现（CR-M2-001 §2.5）。
 *
 * <p>键结构：{@code yl:sw:{dimension}} → ZSET，成员 = {@code 时间戳-随机串}，分值 = 毫秒时间戳。
 *
 * <p>「修剪过期成员 → 写入本次 → 设置过期 → 返回基数」四步由 <b>Lua 脚本单次往返原子执行</b>：若拆成多次客户端调用，
 * 高并发下会出现「读到旧基数」或「修剪把本次刚写入的成员误删」的竞态，导致计数偏低而漏告警。
 *
 * <p>成员串带随机后缀是必要的：同一毫秒内的多次访问若用时间戳作成员，ZSET 会去重合并，计数将系统性偏低。
 */
@Component
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification =
                                "StringRedisTemplate 是 Spring 容器管理的共享线程安全客户端，"
                                        + "由构造器注入是标准 DI 用法，不构成外部可变状态泄露"))
public class RedisSlidingWindowCounter implements SlidingWindowCounter {

    private static final String KEY_PREFIX = "yl:sw:";

    /**
     * 原子「修剪 + 写入 + 过期 + 计数」。
     *
     * <p>KEYS[1] = ZSET 键；ARGV[1] = 保留最小分值（now - window）；ARGV[2] = 本次分值（now）； ARGV[3] =
     * 本次成员；ARGV[4] = 键过期秒数。
     */
    private static final DefaultRedisScript<Long> RECORD_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[1])
                    redis.call('ZADD', KEYS[1], ARGV[2], ARGV[3])
                    redis.call('EXPIRE', KEYS[1], ARGV[4])
                    return redis.call('ZCARD', KEYS[1])
                    """,
                    Long.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public long recordAndCount(String dimension, long windowSeconds) {
        long windowMillis = Math.max(windowSeconds, 1L) * 1000L;
        long now = System.currentTimeMillis();
        String member = now + "-" + UUID.randomUUID();
        Long count =
                redisTemplate.execute(
                        RECORD_SCRIPT,
                        List.of(KEY_PREFIX + dimension),
                        String.valueOf(now - windowMillis),
                        String.valueOf(now),
                        member,
                        String.valueOf(Math.max(windowSeconds, 1L)));
        // 脚本不可用（如序列化异常）时返回 0：宁可少计，也不因计数设施抖动误告警真实用户
        return count == null ? 0L : count;
    }

    @Override
    public boolean tryAcquireOnce(String dimension, long windowSeconds) {
        Boolean acquired =
                redisTemplate
                        .opsForValue()
                        .setIfAbsent(
                                KEY_PREFIX + dimension + ":once",
                                "1",
                                Duration.ofSeconds(Math.max(windowSeconds, 1L)));
        return Boolean.TRUE.equals(acquired);
    }
}
