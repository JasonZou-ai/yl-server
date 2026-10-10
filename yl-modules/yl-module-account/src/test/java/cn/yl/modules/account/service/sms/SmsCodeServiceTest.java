package cn.yl.modules.account.service.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.common.ratelimit.SlidingWindowCounter;
import cn.yl.common.security.SensitiveFieldCodec;
import cn.yl.common.sms.RenderedCaptcha;
import cn.yl.common.sms.SmsCaptchaRenderer;
import cn.yl.common.sms.SmsCaptchaStore;
import cn.yl.common.sms.SmsCodeStore;
import cn.yl.common.sms.SmsPurpose;
import cn.yl.common.sms.SmsScene;
import cn.yl.common.sms.SmsSender;
import cn.yl.common.sms.SmsTicketStore;
import cn.yl.modules.account.config.SmsProperties;
import cn.yl.modules.account.dto.SmsCaptchaResponse;
import cn.yl.modules.account.dto.SmsSendRequest;
import cn.yl.modules.account.dto.SmsSendResponse;
import cn.yl.modules.account.dto.SmsTicketResponse;
import cn.yl.modules.account.dto.SmsVerifyRequest;
import cn.yl.modules.account.service.AuditService;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.beans.factory.ObjectProvider;

/** {@link SmsCodeService} 单测（P2-8-A 验证判据 1–6）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SmsCodeServiceTest {

    private static final String IP = "1.2.3.4";
    private static final String UA = "unit-test-agent";
    private static final String PHONE = "13800008000";

    @Mock private ObjectProvider<SensitiveFieldCodec> codecProvider;

    @Mock private SlidingWindowCounter counter;

    @Mock private SmsCodeStore codeStore;

    @Mock private SmsCaptchaStore captchaStore;

    @Mock private SmsTicketStore ticketStore;

    @Mock private SmsSender sender;

    @Mock private SmsCaptchaRenderer captchaRenderer;

    @Mock private AuditService auditService;

    private SmsProperties properties;

    private SensitiveFieldCodec codec;

    private SmsCodeService service;

    @BeforeEach
    void setUp() {
        properties = new SmsProperties();
        codec = realCodec();
        when(codecProvider.getIfAvailable()).thenReturn(codec);
        service =
                new SmsCodeService(
                        properties,
                        codecProvider,
                        counter,
                        codeStore,
                        captchaStore,
                        ticketStore,
                        sender,
                        captchaRenderer,
                        auditService);
    }

    private static SensitiveFieldCodec realCodec() {
        byte[] master = new byte[32];
        byte[] hash = new byte[32];
        Arrays.fill(master, (byte) 0x11);
        Arrays.fill(hash, (byte) 0x22);
        return new SensitiveFieldCodec(
                Base64.getEncoder().encodeToString(master),
                Base64.getEncoder().encodeToString(hash));
    }

    private SmsSendRequest sendRequest() {
        SmsSendRequest request = new SmsSendRequest();
        request.setPhone(PHONE);
        request.setPurpose("LOGIN");
        request.setScene("WECHAT");
        return request;
    }

    private SmsVerifyRequest verifyRequest() {
        SmsVerifyRequest request = new SmsVerifyRequest();
        request.setPhone(PHONE);
        request.setPurpose("LOGIN");
        request.setScene("WECHAT");
        request.setCode("123456");
        return request;
    }

    // ---------------- SMS-01 ----------------

    @Test
    void generateCaptchaStoresAnswerAndReturnsImage() {
        when(captchaRenderer.render())
                .thenReturn(new RenderedCaptcha("1234", "data:image/png;base64,AAAA"));

        SmsCaptchaResponse response = service.generateCaptcha("sms_send");

        assertNotNull(response.getCaptchaId());
        assertEquals("data:image/png;base64,AAAA", response.getCaptchaImage());
        assertEquals(properties.getCaptcha().getTtlSeconds(), response.getTtl());
        verify(captchaStore).save(anyString(), anyString(), anyLong());
    }

    // ---------------- SMS-02 ----------------

    @Test
    void sendHappyPathReturnsMaskedPhone() {
        when(counter.recordAndCount(anyString(), anyLong())).thenReturn(1L);
        when(codeStore.failureCount(any(), any())).thenReturn(0L);

        SmsSendResponse response = service.send(sendRequest(), IP, UA);

        assertEquals("138****8000", response.getMaskedPhone());
        assertEquals(properties.getCode().getTtlSeconds(), response.getTtl());
        verify(codeStore).save(anyString(), any(), anyString(), anyLong());
        verify(sender).send(anyString(), any(), anyString());
    }

    /** 判据 4：频控超限 → 10429，且不落码、不下发。 */
    @Test
    void sendRateLimited() {
        when(counter.recordAndCount(anyString(), anyLong())).thenReturn(999L);

        BizException ex =
                assertThrows(BizException.class, () -> service.send(sendRequest(), IP, UA));

        assertEquals(ErrorCode.RATE_LIMITED.getCode(), ex.getCode());
        verify(codeStore, never()).save(anyString(), any(), anyString(), anyLong());
        verify(sender, never()).send(anyString(), any(), anyString());
    }

    /** 判据 3：错误锁定（失败累计 ≥5）→ 10429。 */
    @Test
    void sendRejectedWhenLocked() {
        when(counter.recordAndCount(anyString(), anyLong())).thenReturn(1L);
        when(codeStore.failureCount(any(), any())).thenReturn(5L);

        BizException ex =
                assertThrows(BizException.class, () -> service.send(sendRequest(), IP, UA));

        assertEquals(ErrorCode.RATE_LIMITED.getCode(), ex.getCode());
    }

    @Test
    void sendRequiresCaptchaWhenTriggered() {
        when(counter.recordAndCount(anyString(), anyLong())).thenReturn(1L);
        when(counter.recordAndCount(startsWith("sms:cap:ph:"), anyLong())).thenReturn(3L);
        when(codeStore.failureCount(any(), any())).thenReturn(0L);

        BizException ex =
                assertThrows(BizException.class, () -> service.send(sendRequest(), IP, UA));

        assertEquals(ErrorCode.SMS_CAPTCHA_REQUIRED.getCode(), ex.getCode());
    }

    @Test
    void sendRejectsWrongCaptcha() {
        when(counter.recordAndCount(anyString(), anyLong())).thenReturn(1L);
        when(counter.recordAndCount(startsWith("sms:cap:ph:"), anyLong())).thenReturn(3L);
        when(codeStore.failureCount(any(), any())).thenReturn(0L);
        when(captchaStore.consume("cid")).thenReturn(Optional.of("1111"));

        SmsSendRequest request = sendRequest();
        request.setCaptchaId("cid");
        request.setCaptchaCode("2222");

        BizException ex = assertThrows(BizException.class, () -> service.send(request, IP, UA));

        assertEquals(ErrorCode.SMS_CAPTCHA_INVALID.getCode(), ex.getCode());
    }

    @Test
    void sendProceedsAfterValidCaptcha() {
        when(counter.recordAndCount(anyString(), anyLong())).thenReturn(1L);
        when(counter.recordAndCount(startsWith("sms:cap:ph:"), anyLong())).thenReturn(3L);
        when(codeStore.failureCount(any(), any())).thenReturn(0L);
        when(captchaStore.consume("cid")).thenReturn(Optional.of("1234"));

        SmsSendRequest request = sendRequest();
        request.setCaptchaId("cid");
        request.setCaptchaCode("1234");

        assertEquals("138****8000", service.send(request, IP, UA).getMaskedPhone());
    }

    /** 判据 6：应急停发开关 → 20007，绝不静默成功。 */
    @Test
    void sendFailClosedWhenDisabled() {
        properties.setEnabled(false);

        BizException ex =
                assertThrows(BizException.class, () -> service.send(sendRequest(), IP, UA));

        assertEquals(ErrorCode.SMS_SEND_FAILED.getCode(), ex.getCode());
        verify(sender, never()).send(anyString(), any(), anyString());
    }

    /** 判据 6：通道不可用（UnavailableSmsSender 语义）→ 20007。 */
    @Test
    void sendFailClosedWhenSenderUnavailable() {
        when(counter.recordAndCount(anyString(), anyLong())).thenReturn(1L);
        when(codeStore.failureCount(any(), any())).thenReturn(0L);
        org.mockito.Mockito.doThrow(new BizException(ErrorCode.SMS_SEND_FAILED))
                .when(sender)
                .send(anyString(), any(), anyString());

        BizException ex =
                assertThrows(BizException.class, () -> service.send(sendRequest(), IP, UA));

        assertEquals(ErrorCode.SMS_SEND_FAILED.getCode(), ex.getCode());
    }

    @Test
    void sendFailClosedWhenCodecMissing() {
        when(codecProvider.getIfAvailable()).thenReturn(null);
        when(counter.recordAndCount(anyString(), anyLong())).thenReturn(1L);

        BizException ex =
                assertThrows(BizException.class, () -> service.send(sendRequest(), IP, UA));

        assertEquals(ErrorCode.SMS_SEND_FAILED.getCode(), ex.getCode());
    }

    @Test
    void sendRejectsInvalidPhone() {
        SmsSendRequest request = sendRequest();
        request.setPhone("123");

        BizException ex = assertThrows(BizException.class, () -> service.send(request, IP, UA));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    /** 判据 5：SMS-02 响应体只含 maskedPhone + ttl，零「是否已注册」信息；同输入两次结果一致。 */
    @Test
    void sendResponseIsEnumerationNeutral() {
        when(counter.recordAndCount(anyString(), anyLong())).thenReturn(1L);
        when(codeStore.failureCount(any(), any())).thenReturn(0L);

        SmsSendResponse first = service.send(sendRequest(), IP, UA);
        SmsSendResponse second = service.send(sendRequest(), IP, UA);

        assertEquals(first, second);
        Set<String> fields =
                Arrays.stream(SmsSendResponse.class.getDeclaredFields())
                        .map(Field::getName)
                        .collect(Collectors.toSet());
        assertEquals(Set.of("maskedPhone", "ttl"), fields);
    }

    // ---------------- SMS-03 ----------------

    @Test
    void verifySuccessIssuesTicketAndClearsFailures() {
        when(codeStore.failureCount(any(), any())).thenReturn(0L);
        when(codeStore.consumeIfMatches(any(), any(), anyString())).thenReturn(true);

        SmsTicketResponse response = service.verify(verifyRequest());

        assertTrue(response.getTicket() != null && !response.getTicket().isBlank());
        assertEquals(properties.getTicket().getTtlSeconds(), response.getTicketTtl());
        verify(ticketStore)
                .save(
                        anyString(),
                        anyString(),
                        org.mockito.ArgumentMatchers.eq(SmsScene.WECHAT.name()),
                        any(),
                        anyLong());
        verify(codeStore).clearFailure(any(), any());
    }

    /** 判据 2/3：一次性核销失败 → 失败计数 +1 → 20006（错误与过期同码）。 */
    @Test
    void verifyFailureCountsAndReturnsInvalid() {
        when(codeStore.failureCount(any(), any())).thenReturn(0L);
        when(codeStore.consumeIfMatches(any(), any(), anyString())).thenReturn(false);
        when(codeStore.incrementFailure(any(), any(), anyLong())).thenReturn(1L);

        BizException ex = assertThrows(BizException.class, () -> service.verify(verifyRequest()));

        assertEquals(ErrorCode.SMS_CODE_INVALID.getCode(), ex.getCode());
        verify(codeStore).incrementFailure(any(), any(), anyLong());
        verify(ticketStore, never()).save(anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test
    void verifyRejectedWhenLocked() {
        when(codeStore.failureCount(any(), any())).thenReturn(5L);

        BizException ex = assertThrows(BizException.class, () -> service.verify(verifyRequest()));

        assertEquals(ErrorCode.RATE_LIMITED.getCode(), ex.getCode());
        verify(codeStore, never()).consumeIfMatches(any(), any(), anyString());
    }

    /**
     * ★ 红线 §2 / 设计偏离 2：验证码存储键与**全部**频控维度都**不得含明文手机号**（须为 64 位 HMAC-SHA256 摘要）。
     *
     * <p>QA 突变 M4 实证：把 {@code hashPhone} 改成 {@code return phone;} 时门禁**不变红** ⇒ 原断言存在缺口，
     * 本用例即该缺口的补强（并用同一突变反向证明其有区分力）。
     */
    @Test
    void storageAndRateLimitKeysNeverContainPlaintextPhone() {
        when(counter.recordAndCount(anyString(), anyLong())).thenReturn(1L);
        when(codeStore.failureCount(any(), any())).thenReturn(0L);

        service.send(sendRequest(), IP, UA);

        String expectedHash = codec.hashForSearch(PHONE);

        // ① 存储键：必须**等于** codec.hashForSearch(PHONE)，而非明文手机号
        ArgumentCaptor<String> storedHash = ArgumentCaptor.forClass(String.class);
        verify(codeStore).save(storedHash.capture(), any(), anyString(), anyLong());
        assertEquals(
                expectedHash,
                storedHash.getValue(),
                "验证码存储键必须等于 codec.hashForSearch(PHONE)（即 HMAC 摘要），实际：" + storedHash.getValue());
        assertFalse(
                storedHash.getValue().contains(PHONE), "验证码存储键不得含明文手机号：" + storedHash.getValue());

        // ② 频控维度：无明文手机号，且手机号维度一律以内嵌摘要构造
        ArgumentCaptor<String> dimensions = ArgumentCaptor.forClass(String.class);
        verify(counter, atLeastOnce()).recordAndCount(dimensions.capture(), anyLong());
        assertFalse(dimensions.getAllValues().isEmpty(), "频控维度不应为空");
        for (String dimension : dimensions.getAllValues()) {
            assertFalse(dimension.contains(PHONE), "频控维度不得含明文手机号：" + dimension);
        }
        long phoneDimensions =
                dimensions.getAllValues().stream().filter(d -> d.startsWith("sms:rl:ph:")).count();
        assertEquals(3L, phoneDimensions, "同手机号应有 60s/1h/1d 三个维度，实际：" + dimensions.getAllValues());
        assertTrue(
                dimensions.getAllValues().stream()
                        .filter(d -> d.startsWith("sms:rl:ph:"))
                        .allMatch(d -> d.contains(expectedHash)),
                "手机号维度必须以 codec.hashForSearch(PHONE) 构造，实际：" + dimensions.getAllValues());
    }

    /**
     * ★ QA 突变 M3 实证：摘掉 {@code phone:60s} 那条 {@code checkLimit} 时门禁**不变红** ⇒ 原断言（统一 {@code
     * anyString} 超限） 无法定位到具体维度。本用例断言该维度**确实被检查**。
     */
    @Test
    void sendChecksSixtySecondPhoneWindowExplicitly() {
        when(counter.recordAndCount(anyString(), anyLong())).thenReturn(1L);
        when(codeStore.failureCount(any(), any())).thenReturn(0L);

        service.send(sendRequest(), IP, UA);

        ArgumentCaptor<String> dimensions = ArgumentCaptor.forClass(String.class);
        verify(counter, atLeastOnce()).recordAndCount(dimensions.capture(), anyLong());
        assertTrue(
                dimensions.getAllValues().stream()
                        .anyMatch(d -> d.startsWith("sms:rl:ph:") && d.endsWith(":60s")),
                "必须显式检查「同手机号 60s ≤1 次」维度，实际维度：" + dimensions.getAllValues());
    }

    // ---------------- 审计留痕（方案文档 §7.1） ----------------

    /** SMS-02 成功下发须留痕；`detail` 只含枚举，**不含手机号/验证码**。 */
    @Test
    void sendWritesAuditWithoutSensitiveData() {
        when(counter.recordAndCount(anyString(), anyLong())).thenReturn(1L);
        when(codeStore.failureCount(any(), any())).thenReturn(0L);

        service.send(sendRequest(), IP, UA);

        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(auditService).record(eq("SMS_SEND"), eq("SMS"), eq(false), detail.capture());
        assertFalse(detail.getValue().contains(PHONE), "审计 detail 不得含明文手机号");
        assertTrue(detail.getValue().contains("LOGIN"));
    }

    /** SMS-03 校验通过须留痕；`sensitive=false`（本端点无二次验证语义，置 true 会谎报第四闸）。 */
    @Test
    void verifyWritesAuditAsNonSensitive() {
        when(codeStore.failureCount(any(), any())).thenReturn(0L);
        when(codeStore.consumeIfMatches(any(), any(), anyString())).thenReturn(true);

        service.verify(verifyRequest());

        verify(auditService).record(eq("SMS_VERIFY"), eq("SMS"), eq(false), anyString());
    }

    /** 留痕为旁路能力：写审计失败不得阻断核身主链路。 */
    @Test
    void auditFailureDoesNotBlockSend() {
        when(counter.recordAndCount(anyString(), anyLong())).thenReturn(1L);
        when(codeStore.failureCount(any(), any())).thenReturn(0L);
        org.mockito.Mockito.doThrow(new RuntimeException("audit down"))
                .when(auditService)
                .record(anyString(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any());

        assertEquals("138****8000", service.send(sendRequest(), IP, UA).getMaskedPhone());
    }

    @Test
    void purposeEnumParsesCaseInsensitively() {
        assertEquals(SmsPurpose.LOGIN, SmsPurpose.of("login"));
        assertEquals("WXMP", SmsScene.deviceTypeOf("wechat"));
        assertEquals("WEB", SmsScene.deviceTypeOf("unknown"));
        assertEquals("WEB", SmsScene.deviceTypeOf(null));
    }
}
