package cn.yl.modules.account.security;

/**
 * 档案异常访问告警通知端口（CR-M2-001 §2.5）。
 *
 * <p>CR 要求「写入告警事件 → 通知监管角色（SUPERVISOR）」。告警事件写入 {@code audit_log} 由 {@link ArchiveAccessRateGuard}
 * 直接完成，本端口只负责<b>推送</b>环节——本期默认实现为结构化日志（{@link LoggingArchiveAccessAlertNotifier}），生产环境替换为站内信 / 短信 /
 * 告警平台时只需另注册实现并调整 {@code yl.security.archive-access-guard.notifier} 配置，无需改动守卫本身。
 */
public interface ArchiveAccessAlertNotifier {

    /**
     * 推送一条告警给监管角色。
     *
     * <p>实现方应当<b>自行吞掉投递异常</b>并记录日志：告警推送失败不得影响档案读取这一业务主链路。
     *
     * @param alert 告警载荷（不含老人个人信息）
     */
    void notify(ArchiveAccessAlert alert);
}
