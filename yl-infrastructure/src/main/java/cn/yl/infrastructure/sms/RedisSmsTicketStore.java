package cn.yl.infrastructure.sms;

import cn.yl.common.sms.SmsPurpose;
import cn.yl.common.sms.SmsTicket;
import cn.yl.common.sms.SmsTicketStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 短信核身 ticket 存储的 Redis 实现（P2-8-A，设计 §4.4）。
 *
 * <p>键：{@code yl:sms:ticket:{ticket}} → {@code {phoneHash}:{scene}:{purpose}}，TTL 300s。 消费用 {@code
 * GETDEL}（{@code getAndDelete}）保证一次性——同一 ticket 二次兑换必失败，天然防重放。
 */
@Component
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification =
                                "StringRedisTemplate 是 Spring 容器管理的共享线程安全客户端，"
                                        + "由构造器注入是标准 DI 用法，不构成外部可变状态泄露"))
public class RedisSmsTicketStore implements SmsTicketStore {

    private static final String KEY_PREFIX = "yl:sms:ticket:";
    private static final String SEP = ":";
    private static final int PARTS = 3;
    private static final long MIN_TTL_SECONDS = 1L;

    private final StringRedisTemplate redisTemplate;

    @Override
    public void save(
            String ticket, String phoneHash, String scene, SmsPurpose purpose, long ttlSeconds) {
        String value = phoneHash + SEP + scene + SEP + purpose.name();
        redisTemplate
                .opsForValue()
                .set(
                        KEY_PREFIX + ticket,
                        value,
                        Duration.ofSeconds(Math.max(ttlSeconds, MIN_TTL_SECONDS)));
    }

    @Override
    public Optional<SmsTicket> consume(String ticket) {
        if (ticket == null || ticket.isBlank()) {
            return Optional.empty();
        }
        String raw = redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + ticket);
        return Optional.ofNullable(raw).map(this::parse);
    }

    /** 解析 {@code {phoneHash}:{scene}:{purpose}}；格式非法返回 {@code null}（由 {@code map} 收敛为空）。 */
    private SmsTicket parse(String raw) {
        String[] parts = raw.split(SEP, PARTS);
        if (parts.length < PARTS) {
            return null;
        }
        try {
            return new SmsTicket(parts[0], parts[1], SmsPurpose.valueOf(parts[2]));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
