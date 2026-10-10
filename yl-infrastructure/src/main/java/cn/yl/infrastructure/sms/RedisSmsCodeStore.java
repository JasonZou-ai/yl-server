package cn.yl.infrastructure.sms;

import cn.yl.common.sms.SmsCodeStore;
import cn.yl.common.sms.SmsPurpose;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

/**
 * 短信验证码存储的 Redis 实现（P2-8-A，设计 §4.4）。
 *
 * <p>键结构（全摘要，零明文手机号）：
 *
 * <ul>
 *   <li>{@code yl:sms:code:{purpose}:{phoneHash}} → {@code {sha256(code)}:{attemptCount}}，TTL 300s
 *   <li>{@code yl:sms:err:{purpose}:{phoneHash}} → 失败累计次数，TTL 900s
 * </ul>
 *
 * <p><b>★ 为什么值存 {@code sha256(code)} 而非明文</b>（设计 §2 偏离 5）：防 Redis 快照 / 误读泄露。 该摘要是 JDK {@code
 * MessageDigest} 计算、零依赖；<b>不承担抗离线爆破</b>——码空间 10⁶ + TTL 300s， 攻击者须先取得 Redis 访问权，此取舍已显式接受。
 *
 * <p><b>★ 「比对并焚毁」必须单次往返原子执行</b>：{@link #consumeIfMatches} 用 {@link DefaultRedisScript} 一次完成「GET 比对
 * → 相等则 DEL」；若拆成「先 GET 再 DEL」两步，高并发下同一验证码可被双消费（TOCTOU）。
 */
@Component
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification =
                                "StringRedisTemplate 是 Spring 容器管理的共享线程安全客户端，"
                                        + "由构造器注入是标准 DI 用法，不构成外部可变状态泄露"))
public class RedisSmsCodeStore implements SmsCodeStore {

    private static final String CODE_PREFIX = "yl:sms:code:";
    private static final String ERR_PREFIX = "yl:sms:err:";
    private static final String SEP = ":";
    private static final long MIN_TTL_SECONDS = 1L;

    /**
     * 原子「比对并焚毁」。
     *
     * <p>KEYS[1] = 验证码键；ARGV[1] = 待比对 {@code sha256(code)}。返回值 1 = 命中并已删除，0 = 未命中。
     */
    private static final DefaultRedisScript<Long> CONSUME_SCRIPT =
            new DefaultRedisScript<>(
                    """
                    local v = redis.call('GET', KEYS[1])
                    if not v then return 0 end
                    local sep = string.find(v, ':')
                    local stored = sep and string.sub(v, 1, sep - 1) or v
                    if stored == ARGV[1] then
                      redis.call('DEL', KEYS[1])
                      return 1
                    end
                    return 0
                    """,
                    Long.class);

    private final StringRedisTemplate redisTemplate;

    @Override
    public void save(String phoneHash, SmsPurpose purpose, String code, long ttlSeconds) {
        redisTemplate
                .opsForValue()
                .set(
                        codeKey(phoneHash, purpose),
                        sha256Hex(code) + SEP + "0",
                        Duration.ofSeconds(Math.max(ttlSeconds, MIN_TTL_SECONDS)));
    }

    @Override
    public boolean consumeIfMatches(String phoneHash, SmsPurpose purpose, String code) {
        if (phoneHash == null || code == null) {
            return false;
        }
        Long result =
                redisTemplate.execute(
                        CONSUME_SCRIPT, List.of(codeKey(phoneHash, purpose)), sha256Hex(code));
        return result != null && result == 1L;
    }

    @Override
    public long incrementFailure(String phoneHash, SmsPurpose purpose, long lockTtlSeconds) {
        String key = errKey(phoneHash, purpose);
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // 首次累加才设置过期，避免后续累加刷新 TTL 导致锁定窗口被无限延长
            redisTemplate.expire(
                    key, Duration.ofSeconds(Math.max(lockTtlSeconds, MIN_TTL_SECONDS)));
        }
        return count == null ? 0L : count;
    }

    @Override
    public long failureCount(String phoneHash, SmsPurpose purpose) {
        String value = redisTemplate.opsForValue().get(errKey(phoneHash, purpose));
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            // 值被外部污染时按 0 处理（不因计数设施脏数据而误锁真实用户）
            return 0L;
        }
    }

    @Override
    public void clearFailure(String phoneHash, SmsPurpose purpose) {
        redisTemplate.delete(errKey(phoneHash, purpose));
    }

    private String codeKey(String phoneHash, SmsPurpose purpose) {
        return CODE_PREFIX + purpose.name() + SEP + phoneHash;
    }

    private String errKey(String phoneHash, SmsPurpose purpose) {
        return ERR_PREFIX + purpose.name() + SEP + phoneHash;
    }

    /** JDK 零依赖 SHA-256 十六进制摘要。 */
    private static String sha256Hex(String code) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，理论上不可达
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }
}
