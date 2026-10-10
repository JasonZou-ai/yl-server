package cn.yl.infrastructure.sms;

import cn.yl.common.sms.SmsCaptchaStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * 图形验证码答案存储的 Redis 实现（P2-8-A，设计 §4.4）。
 *
 * <p>键：{@code yl:sms:captcha:{captchaId}} → 答案，TTL 120s。消费用 {@code GETDEL}（{@code
 * getAndDelete}）保证「一次有效」——无论用户填对与否，取值即焚毁，杜绝重放。
 */
@Component
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification =
                                "StringRedisTemplate 是 Spring 容器管理的共享线程安全客户端，"
                                        + "由构造器注入是标准 DI 用法，不构成外部可变状态泄露"))
public class RedisSmsCaptchaStore implements SmsCaptchaStore {

    private static final String KEY_PREFIX = "yl:sms:captcha:";
    private static final long MIN_TTL_SECONDS = 1L;

    private final StringRedisTemplate redisTemplate;

    @Override
    public void save(String captchaId, String answer, long ttlSeconds) {
        redisTemplate
                .opsForValue()
                .set(
                        KEY_PREFIX + captchaId,
                        answer,
                        Duration.ofSeconds(Math.max(ttlSeconds, MIN_TTL_SECONDS)));
    }

    @Override
    public Optional<String> consume(String captchaId) {
        if (captchaId == null || captchaId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(
                redisTemplate.opsForValue().getAndDelete(KEY_PREFIX + captchaId));
    }
}
