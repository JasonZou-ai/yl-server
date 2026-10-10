package cn.yl.infrastructure.sms;

import cn.yl.common.sms.SmsSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 短信通道装配（P2-8-A，设计 §4.5）。
 *
 * <p>按 {@code yl.sms.provider} 装配**唯一**的 {@link SmsSender} Bean，三类取值互斥且覆盖全部合法配置：
 *
 * <ul>
 *   <li>{@code log}（缺省，{@code matchIfMissing=true}）→ {@link LoggingSmsSender}
 *   <li>{@code tencent} → {@link UnavailableSmsSender}（fail-closed）
 *   <li>{@code aliyun} → {@link UnavailableSmsSender}（fail-closed）
 * </ul>
 *
 * <p>两个真实 provider 各装一个 fail-closed 桩，确保 Spring 不会因「配了 provider 却没有对应 Bean」 启动失败，也不会静默降级（红线 §5）。
 */
@Configuration
public class SmsSenderConfig {

    /** dev 默认日志桩（provider=log 或缺省）。 */
    @Bean
    @ConditionalOnProperty(
            prefix = "yl.sms",
            name = "provider",
            havingValue = "log",
            matchIfMissing = true)
    public SmsSender loggingSmsSender(@Value("${yl.sms.dev-log-code:false}") boolean devLogCode) {
        return new LoggingSmsSender(devLogCode);
    }

    /** 腾讯云短信（A 段未接入 → fail-closed）。 */
    @Bean
    @ConditionalOnProperty(prefix = "yl.sms", name = "provider", havingValue = "tencent")
    public SmsSender tencentSmsSender() {
        return new UnavailableSmsSender("tencent");
    }

    /** 阿里云短信（A 段未接入 → fail-closed）。 */
    @Bean
    @ConditionalOnProperty(prefix = "yl.sms", name = "provider", havingValue = "aliyun")
    public SmsSender aliyunSmsSender() {
        return new UnavailableSmsSender("aliyun");
    }
}
