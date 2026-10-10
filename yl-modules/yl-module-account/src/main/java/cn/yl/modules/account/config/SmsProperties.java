package cn.yl.modules.account.config;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 短信核身通道配置（P2-8-A，设计 §6，前缀 {@code yl.sms}）。
 *
 * <p>{@code dev-log-code} 仅 dev profile 置 true（{@code application-dev.yml}），生产必须保持 false；由配置门禁断言回归。
 */
@Getter
@Setter
@Component
@ConfigurationProperties(prefix = "yl.sms")
@SuppressFBWarnings(
        value = "EI_EXPOSE_REP",
        justification =
                "Spring Boot @ConfigurationProperties 绑定需要可变的嵌套 POJO 与 getter/setter，"
                        + "暴露内部嵌套配置对象是框架绑定契约的一部分，非外部可变状态泄露")
public class SmsProperties {

    /** 总开关（应急停发，合规清单 F4）：false 时 SMS-02 直接 fail-closed。 */
    private boolean enabled = true;

    /** 通道 provider：{@code log}（缺省）/ {@code tencent} / {@code aliyun}。 */
    private String provider = "log";

    /** 是否在日志打印验证码明文（★ 仅 dev profile 置 true）。 */
    private boolean devLogCode = false;

    private Code code = new Code();

    private Ticket ticket = new Ticket();

    private Captcha captcha = new Captcha();

    private Limits limits = new Limits();

    /** 验证码参数。 */
    @Getter
    @Setter
    public static class Code {
        /** 验证码有效期（秒）。 */
        private long ttlSeconds = 300;

        /** 验证码长度。 */
        private int length = 6;
    }

    /** ticket 参数。 */
    @Getter
    @Setter
    public static class Ticket {
        /** ticket 有效期（秒）。 */
        private long ttlSeconds = 300;
    }

    /** 图形验证码参数。 */
    @Getter
    @Setter
    public static class Captcha {
        /** 图形验证码有效期（秒）。 */
        private long ttlSeconds = 120;

        /** 图形验证码长度。 */
        private int length = 4;
    }

    /** 频控与锁定阈值（设计 §4.3，对应 {@code yl.sms.limits.*}）。 */
    @Getter
    @Setter
    public static class Limits {
        /** 同手机号 60s 上限。 */
        private long phone60s = 1;

        /** 同手机号 1h 上限。 */
        private long phone1h = 5;

        /** 同手机号 1d 上限。 */
        private long phone1d = 10;

        /** 同 IP 1h 上限。 */
        private long ip1h = 30;

        /** 同设备指纹 1d 上限。 */
        private long device1d = 20;

        /** 图形验证触发（同手机号 1h）。 */
        private long captchaPhone1h = 3;

        /** 图形验证触发（同 IP 1h）。 */
        private long captchaIp1h = 10;

        /** 错误锁定阈值（失败累计 ≥ 此值即锁）。 */
        private long errLock = 5;

        /** 错误锁定时长（秒）。 */
        private long errLockTtlSeconds = 900;
    }
}
