package cn.yl.infrastructure.sms;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yl.common.sms.SmsPurpose;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link LoggingSmsSender} 的**行为门禁**（P2-8-A，设计 §4.5/§4.6）。
 *
 * <p>补强依据：QA 突变 **M13** 实证 —— 本类原本**零测试**，`yl.sms.dev-log-code` 只有「yaml 值门禁」 （{@code
 * SmsDevLogCodeConfigTest}），**没有「行为门禁」**（开关为 false 时是否**真的**抑制验证码）。本类即该缺口补强， 并已用突变反向证明有区分力。
 *
 * <p>红线（合规清单 D3）：**任何情况下都不得把明文手机号或验证码写进日志**——注意后者在 dev 模式是**有意例外**， 故必须同时钉死「例外只在开关打开时发生」。
 */
class LoggingSmsSenderTest {

    private static final String PHONE = "13800008000";
    private static final String MASKED = "138****8000";
    private static final String CODE = "482931";

    @Test
    void hidesCodeWhenDevLogCodeDisabled() {
        List<String> logs =
                LogCapture.messagesOf(
                        LoggingSmsSender.class,
                        () -> new LoggingSmsSender(false).send(PHONE, SmsPurpose.LOGIN, CODE));

        String joined = String.join(" | ", logs);
        assertTrue(joined.contains(MASKED), "应打印掩码手机号：" + joined);
        assertFalse(joined.contains(CODE), "★ dev-log-code=false 时验证码不得进日志：" + joined);
        assertFalse(joined.contains(PHONE), "★ 明文手机号不得进日志：" + joined);
    }

    @Test
    void printsCodeWithDevOnlyPrefixWhenEnabled() {
        List<String> logs =
                LogCapture.messagesOf(
                        LoggingSmsSender.class,
                        () -> new LoggingSmsSender(true).send(PHONE, SmsPurpose.LOGIN, CODE));

        String joined = String.join(" | ", logs);
        assertTrue(joined.contains(CODE), "dev-log-code=true 时应打印验证码（联调必需）：" + joined);
        assertTrue(joined.contains("[DEV-ONLY]"), "必须带 [DEV-ONLY] 前缀以示风险：" + joined);
        assertTrue(joined.contains(MASKED), "应打印掩码手机号：" + joined);
        assertFalse(joined.contains(PHONE), "★ 即便 dev 模式，明文手机号也不得进日志：" + joined);
    }
}
