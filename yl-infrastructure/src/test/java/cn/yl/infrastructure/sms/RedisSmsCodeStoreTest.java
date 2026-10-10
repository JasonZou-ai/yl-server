package cn.yl.infrastructure.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yl.common.sms.SmsPurpose;
import java.lang.reflect.Field;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * {@link RedisSmsCodeStore} 单测（P2-8-A 验证判据 1/2/3）。
 *
 * <p>不连 Redis：以 mock 覆盖「不落明文 / 一次性核销语义 / 失败计数 / Lua 脚本字符串」四项可断言点。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RedisSmsCodeStoreTest {

    @Mock private StringRedisTemplate redisTemplate;

    @Mock private ValueOperations<String, String> valueOperations;

    private RedisSmsCodeStore store;

    @BeforeEach
    void setUp() {
        store = new RedisSmsCodeStore(redisTemplate);
    }

    /** 判据 1：存 Redis 的值为 {@code sha256(code)}:{@code 0}，绝不含明文验证码。 */
    @Test
    void saveStoresSha256NotPlaintext() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        store.save("PHONEHASH", SmsPurpose.LOGIN, "123456", 300);

        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> value = ArgumentCaptor.forClass(String.class);
        verify(valueOperations).set(key.capture(), value.capture(), any(Duration.class));
        assertTrue(key.getValue().contains("PHONEHASH"));
        assertTrue(key.getValue().contains("LOGIN"));
        assertFalse(value.getValue().contains("123456"), "Redis 值不得含验证码明文");
        assertTrue(value.getValue().endsWith(":0"));
    }

    /** 判据 2：Lua 命中返回 1 → true；未命中返回 0 → false；设施抖动返回 null → false（保守拒绝）。 */
    @Test
    void consumeIfMatchesMapsLuaResult() {
        when(redisTemplate.execute(any(), anyList(), any())).thenReturn(1L);
        assertTrue(store.consumeIfMatches("PHONEHASH", SmsPurpose.LOGIN, "123456"));

        when(redisTemplate.execute(any(), anyList(), any())).thenReturn(0L);
        assertFalse(store.consumeIfMatches("PHONEHASH", SmsPurpose.LOGIN, "000000"));

        when(redisTemplate.execute(any(), anyList(), any())).thenReturn(null);
        assertFalse(store.consumeIfMatches("PHONEHASH", SmsPurpose.LOGIN, "123456"));
    }

    @Test
    void consumeIfMatchesRejectsNullInputs() {
        assertFalse(store.consumeIfMatches(null, SmsPurpose.LOGIN, "123456"));
        assertFalse(store.consumeIfMatches("PHONEHASH", SmsPurpose.LOGIN, null));
    }

    /** 判据 3：失败计数首次累加设置 TTL，返回累计值。 */
    @Test
    void incrementFailureSetsTtlOnFirstIncrement() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.increment(anyString())).thenReturn(1L);

        assertEquals(1L, store.incrementFailure("PHONEHASH", SmsPurpose.LOGIN, 900));

        verify(redisTemplate).expire(anyString(), any(Duration.class));
    }

    @Test
    void failureCountParsesAndDegrades() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenReturn("3");
        assertEquals(3L, store.failureCount("PHONEHASH", SmsPurpose.LOGIN));

        when(valueOperations.get(anyString())).thenReturn(null);
        assertEquals(0L, store.failureCount("PHONEHASH", SmsPurpose.LOGIN));

        when(valueOperations.get(anyString())).thenReturn("not-a-number");
        assertEquals(0L, store.failureCount("PHONEHASH", SmsPurpose.LOGIN));
    }

    @Test
    void clearFailureDeletesKey() {
        store.clearFailure("PHONEHASH", SmsPurpose.LOGIN);
        verify(redisTemplate).delete(anyString());
    }

    /**
     * 原子性来源断言（★ 设计红线）：比对并焚毁的 Lua 脚本同时包含 GET 与 DEL，由单次往返完成。
     *
     * <p>若有人把脚本改成两步客户端调用，本断言即失效——把「原子」从口头承诺变成可回归断言。
     */
    @Test
    void consumeScriptIsAtomicGetThenDel() throws Exception {
        Field field = RedisSmsCodeStore.class.getDeclaredField("CONSUME_SCRIPT");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        DefaultRedisScript<Long> script = (DefaultRedisScript<Long>) field.get(null);
        assertNotNull(script);
        String text = script.getScriptAsString();
        assertTrue(text.contains("'GET'"));
        assertTrue(text.contains("'DEL'"));
    }
}
