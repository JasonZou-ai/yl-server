package cn.yl.infrastructure.sms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.common.sms.SmsPurpose;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link UnavailableSmsSender} 的行为门禁（P2-8-A，设计 §4.5）。
 *
 * <p>红线：**通道不可用时必须 fail-closed 且可观测**——不得静默假装成功（卡面原话「不造轮子也不假装存在」）。 A 段以本类占位真实 provider 的两个取值，确保
 * Spring 既不因缺 Bean 启动失败，也不静默降级。
 */
class UnavailableSmsSenderTest {

    private static final String PHONE = "13800008000";
    private static final String MASKED = "138****8000";
    private static final String CODE = "482931";

    @Test
    void failsClosedWithDedicatedErrorCode() {
        BizException ex =
                assertThrows(
                        BizException.class,
                        () ->
                                new UnavailableSmsSender("tencent")
                                        .send(PHONE, SmsPurpose.LOGIN, CODE));

        assertEquals(
                ErrorCode.SMS_SEND_FAILED.getCode(),
                ex.getCode(),
                "真实 provider 未接入时必须抛 SMS_SEND_FAILED（fail-closed）");
    }

    @Test
    void logsProviderAndMaskedPhoneButNeverCode() {
        List<String> logs =
                LogCapture.messagesOf(
                        UnavailableSmsSender.class,
                        () ->
                                assertThrows(
                                        BizException.class,
                                        () ->
                                                new UnavailableSmsSender("tencent")
                                                        .send(PHONE, SmsPurpose.LOGIN, CODE)));

        String joined = String.join(" | ", logs);
        assertTrue(joined.contains("tencent"), "应记录未接入的 provider（可观测）：" + joined);
        assertTrue(joined.contains(MASKED), "应打印掩码手机号：" + joined);
        assertFalse(joined.contains(PHONE), "★ 明文手机号不得进日志：" + joined);
        assertFalse(joined.contains(CODE), "★ 验证码不得进日志：" + joined);
    }
}
