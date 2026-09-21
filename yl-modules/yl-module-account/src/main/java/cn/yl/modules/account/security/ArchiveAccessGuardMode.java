package cn.yl.modules.account.security;

import java.util.Locale;

/**
 * 档案异常访问守卫的处置模式（CR-M2-001 §2.5 · 告警响应 SLA 配套）。
 *
 * <p>本期默认 {@link #ALERT}（写审计 + 通知监管角色，<b>不拦截</b>）。模式外置为配置项 {@code
 * yl.security.archive-access-guard.mode}，可在不重新发版的前提下按运行阶段调整强度。
 *
 * <p><b>为什么默认不是 ENFORCE</b>：{@code elder:archive:read} 是非敏感权限点，契约未声明「超频即拒绝」。开启 ENFORCE 会让
 * 客户端在无预警的情况下收到 429，属<b>契约破坏性变更</b>，超出 CR-M2-001 授权范围。故 ENFORCE 仅作为能力位预留， 需随 C/D
 * 模块一并完成契约变更（补充错误码与重试语义）后方可启用。
 */
public enum ArchiveAccessGuardMode {

    /** 完全旁路：不计数、不告警，不产生任何 Redis I/O。用于故障排查期临时摘除。 */
    OFF(false, false),

    /** 只写审计留痕，不推送通知。用于灰度期（先攒数据、不打扰监管侧）。 */
    AUDIT(true, false),

    /** 写审计 + 通知监管角色（<b>默认</b>）。仅监控，不阻断业务主链路。 */
    ALERT(true, true),

    /**
     * 写审计 + 通知 + 拒绝本次请求（{@code ErrorCode.RATE_LIMITED}）。
     *
     * <p><b>启用前置条件</b>：契约已声明该错误码与重试语义；否则客户端会收到未约定的 429。
     */
    ENFORCE(true, true);

    private final boolean track;

    private final boolean notify;

    ArchiveAccessGuardMode(boolean track, boolean notify) {
        this.track = track;
        this.notify = notify;
    }

    /** 该模式下是否需要计数（OFF 直接旁路）。 */
    public boolean tracks() {
        return track;
    }

    /** 该模式下是否推送监管通知。 */
    public boolean notifies() {
        return notify;
    }

    /** 该模式下是否直接拒绝请求。 */
    public boolean enforces() {
        return this == ENFORCE;
    }

    /**
     * 解析配置值；未知或空值回落 {@link #ALERT}（而非 OFF）—— 配置写错时保持监控强度，避免静默关掉护栏。
     *
     * @param raw 原始配置值
     * @return 解析后的模式
     */
    public static ArchiveAccessGuardMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return ALERT;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT);
        for (ArchiveAccessGuardMode mode : values()) {
            if (mode.name().equals(normalized)) {
                return mode;
            }
        }
        return ALERT;
    }
}
