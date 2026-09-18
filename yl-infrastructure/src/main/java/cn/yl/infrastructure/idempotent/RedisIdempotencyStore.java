package cn.yl.infrastructure.idempotent;

import cn.yl.common.idempotent.IdempotencyStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 幂等存储的 Redis 实现（B1-2 / 子任务 rfVTRf）。
 *
 * <p>键结构：{@code yl:idem:{idempotencyKey}}。 值语义：占位符 {@link #PROCESSING} 表示「处理中」；其它值为首次响应的 JSON 快照。
 *
 * <p>使用 {@code SET NX EX} 保证并发下只有一个请求能拿到执行权（原子）。 DB 兜底表 {@code idempotent_record}（见
 * 02_schema.sql）用于 Redis 失效后的事后审计与补偿重放。
 */
@Component
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification =
                                "StringRedisTemplate 是 Spring 容器管理的共享线程安全客户端，"
                                        + "由构造器注入是标准 DI 用法，不构成外部可变状态泄露"))
public class RedisIdempotencyStore implements IdempotencyStore {

    /** 处理中占位值 */
    public static final String PROCESSING = "__PROCESSING__";

    private static final String KEY_PREFIX = "yl:idem:";

    private final StringRedisTemplate redisTemplate;

    @Override
    public boolean tryAcquire(String key, long ttlSeconds) {
        Boolean acquired =
                redisTemplate
                        .opsForValue()
                        .setIfAbsent(redisKey(key), PROCESSING, Duration.ofSeconds(ttlSeconds));
        return Boolean.TRUE.equals(acquired);
    }

    @Override
    public boolean markProcessing(String key, long ttlSeconds) {
        return tryAcquire(key, ttlSeconds);
    }

    @Override
    public String getResponse(String key) {
        String value = redisTemplate.opsForValue().get(redisKey(key));
        if (value == null || PROCESSING.equals(value)) {
            return null;
        }
        return value;
    }

    @Override
    public void saveResponse(String key, String responseJson, long ttlSeconds) {
        redisTemplate
                .opsForValue()
                .set(redisKey(key), responseJson, Duration.ofSeconds(ttlSeconds));
    }

    @Override
    public void release(String key) {
        redisTemplate.delete(redisKey(key));
    }

    private String redisKey(String key) {
        return KEY_PREFIX + key;
    }
}
