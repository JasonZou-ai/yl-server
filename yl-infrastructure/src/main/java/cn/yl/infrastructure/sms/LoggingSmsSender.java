package cn.yl.infrastructure.sms;

import cn.yl.common.security.MaskUtils;
import cn.yl.common.sms.SmsPurpose;
import cn.yl.common.sms.SmsSender;
import lombok.extern.slf4j.Slf4j;

/**
 * dev 日志桩短信通道（P2-8-A，设计 §4.5）。
 *
 * <p>恒「下发成功」，不产生任何外部调用。手机号一律 {@link MaskUtils#maskPhone} 掩码；验证码<b>仅当</b> {@code
 * yl.sms.dev-log-code=true} 时随日志打印（联调必需），否则只打印掩码手机号。
 *
 * <p>★ 合规红线（设计 §4.6）：{@code yl.sms.dev-log-code} 仅 dev profile 置 true，生产保持 false——由 配置门禁断言回归。
 */
@Slf4j
public class LoggingSmsSender implements SmsSender {

    private final boolean devLogCode;

    /**
     * @param devLogCode 是否在日志中打印验证码明文（仅 dev 联调开启）
     */
    public LoggingSmsSender(boolean devLogCode) {
        this.devLogCode = devLogCode;
    }

    @Override
    public void send(String phone, SmsPurpose purpose, String code) {
        String maskedPhone = MaskUtils.maskPhone(phone);
        if (devLogCode) {
            log.info(
                    "[DEV-ONLY] 短信验证码下发（日志桩，验证码仅 dev 可见）：phone={} purpose={} code={}",
                    maskedPhone,
                    purpose,
                    code);
        } else {
            log.info("短信验证码下发（日志桩，验证码已隐藏）：phone={} purpose={}", maskedPhone, purpose);
        }
    }
}
