package cn.yl.modules.account.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 告警通知的默认实现：结构化日志（CR-M2-001 §2.5，MVP）。
 *
 * <p>用 {@code @ConditionalOnProperty} 而非 {@code @ConditionalOnMissingBean} 做可替换点：后者在非自动配置的普通
 * {@code @Configuration} 中依赖 Bean 注册顺序，行为不稳定；前者由配置显式开关，生产接入站内信 / 告警平台时 <b>只需设置 {@code
 * yl.security.archive-access-guard.notifier=remote} 并注册新的实现类</b>，语义清晰且无顺序耦合。
 *
 * <p>本期不接真实推送渠道的原因：告警接收方与渠道（站内信 / 短信 / 监管平台）属集成层依赖，与 B2 集成层跟踪项同批排期。
 */
@Slf4j
@Component
@ConditionalOnProperty(
        name = "yl.security.archive-access-guard.notifier",
        havingValue = "logging",
        matchIfMissing = true)
public class LoggingArchiveAccessAlertNotifier implements ArchiveAccessAlertNotifier {

    @Override
    public void notify(ArchiveAccessAlert alert) {
        log.warn(
                "[档案异常访问告警] userId={} roles={} count={} > threshold={}（滑动 {} 分钟）→ 待通知监管角色 SUPERVISOR",
                alert.userId(),
                alert.roles(),
                alert.count(),
                alert.threshold(),
                alert.windowMinutes());
    }
}
