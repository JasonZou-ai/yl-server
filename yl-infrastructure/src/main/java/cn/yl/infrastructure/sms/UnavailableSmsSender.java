package cn.yl.infrastructure.sms;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.common.security.MaskUtils;
import cn.yl.common.sms.SmsPurpose;
import cn.yl.common.sms.SmsSender;
import lombok.extern.slf4j.Slf4j;

/**
 * 未接入通道的 fail-closed 桩（P2-8-A，设计 §4.5）。
 *
 * <p>当 {@code yl.sms.provider} 配为真实服务商（{@code tencent} / {@code aliyun}）但 A 段尚未接入 SDK 时装配本类： {@link
 * #send} 直接抛 {@link ErrorCode#SMS_SEND_FAILED}。
 *
 * <p>★ 为什么 A 段就要有这个类：若真实 provider 下没有 Bean，Spring 会因缺依赖启动失败（把「配置错误」变成
 * 「服务不可用」）；或静默降级（红线明令禁止的「静默假装成功」）。显式 fail-closed 是唯一正确解。
 */
@Slf4j
public class UnavailableSmsSender implements SmsSender {

    private final String provider;

    /**
     * @param provider 未接入的服务商标识（仅用于日志）
     */
    public UnavailableSmsSender(String provider) {
        this.provider = provider;
    }

    @Override
    public void send(String phone, SmsPurpose purpose, String code) {
        log.warn(
                "短信通道未接入（provider={}），拒绝下发：phone={} purpose={}",
                provider,
                MaskUtils.maskPhone(phone),
                purpose);
        throw new BizException(ErrorCode.SMS_SEND_FAILED);
    }
}
