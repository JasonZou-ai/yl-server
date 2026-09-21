package cn.yl.modules.account.security;

import cn.yl.api.security.LoginUser;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.common.ratelimit.SlidingWindowCounter;
import cn.yl.modules.account.domain.PermissionCode;
import cn.yl.modules.account.service.AuditService;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 档案接口异常访问告警守卫（CR-M2-001 §2.5，PM 补充意见）。
 *
 * <p><b>背景</b>：CR-M2-001 把 {@code elder:archive:read} 授予全部 5 个角色，可见范围改由第三闸 {@code data_scope}
 * 控制——权限点层面的「✅」不再具备限速含义。故必须补一层<b>行为异常检测</b>，兜住「非护理角色批量拉档」风险。
 *
 * <p><b>判定</b>：同一账号对档案类接口（{@code GET /elders}、{@code GET /elders/{elderId}}，对应权限点 {@code
 * elder:archive:read}）在<b>滑动 1 小时</b>内调用次数 <b>超过 N</b>（默认 50，参数外置可配）时： 写入 {@code audit_log} 告警事件 →
 * 通知监管角色（SUPERVISOR）。
 *
 * <p><b>三个刻意的设计取舍</b>：
 *
 * <ol>
 *   <li><b>默认只告警、不拦截</b>。CR 把「降级为单次二次验证」列为<b>可选</b>项，且档案读取是非敏感权限点——强制二次验证 会使客户端在无 {@code
 *       X-Second-Verify-Token} 的情况下直接失败，属契约破坏性变更，超出本 CR 授权范围。处置强度 外置为 {@link
 *       ArchiveAccessGuardMode}（默认 {@code ALERT}），{@code ENFORCE} 仅作能力位预留，须先变更契约方可启用。
 *   <li><b>失败开放</b>。计数 / 去重 / 通知任一环节异常均只记警告，绝不向调用方抛错——告警是旁路监控能力，不能因监控设施抖动而阻断档案读取这一业务主链路。
 *   <li><b>独立组件</b>。按 CR 建议做成独立守卫而非把逻辑塞进 {@code PermissionAspect}；切面仅保留一行委派，鉴权链路的职责不被稀释。
 * </ol>
 *
 * <p>配置（{@code yl.security.archive-access-guard.*}）：{@code enabled} / {@code mode} / {@code
 * threshold} / {@code window-minutes} / {@code monitored-roles} / {@code notifier}。阈值 ≤0
 * 或监控角色为空时按「未启用」处理并告警， 而非让应用启动失败（沿用 {@code SensitiveFieldConfig} 的既定取舍）。
 */
@Slf4j
@Component
public class ArchiveAccessRateGuard {

    /** 默认监控对象：PRD §2.1 中视为「非护理角色」的三类。 */
    public static final String DEFAULT_MONITORED_ROLES = "ELDER,FAMILY,SUPERVISOR";

    /** 触发本守卫的权限点（档案类读操作）。 */
    private static final Set<String> TRACKED_PERMISSIONS =
            Set.of(PermissionCode.ELDER_ARCHIVE_READ);

    private static final String COUNT_DIMENSION_PREFIX = "yl:arch:count:";

    private static final String ALERT_DIMENSION_PREFIX = "yl:arch:alert:";

    private static final String ALERT_ACTION = "ARCHIVE_ACCESS_ALERT";

    private static final String ALERT_BIZ_TYPE = "ELDER";

    private static final long DEFAULT_WINDOW_MINUTES = 60L;

    private final boolean enabled;

    private final ArchiveAccessGuardMode mode;

    private final int threshold;

    private final long windowMinutes;

    private final Set<String> monitoredRoles;

    private final SlidingWindowCounter counter;

    private final AuditService auditService;

    private final ArchiveAccessAlertNotifier notifier;

    /**
     * @param enabled 总开关
     * @param mode 处置模式（默认 {@code alert}；未知值回落 {@code alert}）
     * @param threshold 阈值 N（滑动窗口内允许的最大调用次数，超过即告警）
     * @param windowMinutes 滑动窗口长度（分钟，默认 60 = 1 小时）
     * @param monitoredRolesCsv 监控角色码（逗号分隔，默认仅非护理角色）
     * @param counter 滑动窗口计数端口
     * @param auditService 审计留痕服务（写告警事件）
     * @param notifier 告警推送端口
     */
    public ArchiveAccessRateGuard(
            @Value("${yl.security.archive-access-guard.enabled:true}") boolean enabled,
            @Value("${yl.security.archive-access-guard.mode:alert}") String mode,
            @Value("${yl.security.archive-access-guard.threshold:50}") int threshold,
            @Value("${yl.security.archive-access-guard.window-minutes:60}") long windowMinutes,
            @Value(
                            "${yl.security.archive-access-guard.monitored-roles:"
                                    + DEFAULT_MONITORED_ROLES
                                    + "}")
                    String monitoredRolesCsv,
            SlidingWindowCounter counter,
            AuditService auditService,
            ArchiveAccessAlertNotifier notifier) {
        this.mode = ArchiveAccessGuardMode.parse(mode);
        this.threshold = threshold;
        this.windowMinutes =
                windowMinutes <= 0 || windowMinutes > 24 * 60
                        ? DEFAULT_WINDOW_MINUTES
                        : windowMinutes;
        this.monitoredRoles = parseRoles(monitoredRolesCsv);
        this.counter = counter;
        this.auditService = auditService;
        this.notifier = notifier;
        this.enabled =
                enabled && this.mode.tracks() && threshold > 0 && !this.monitoredRoles.isEmpty();
        if (this.mode.enforces()) {
            log.warn(
                    "档案异常访问守卫处于 ENFORCE 模式：超阈值将直接拒绝请求（{}）。启用前须确认契约已声明该错误码与重试语义",
                    ErrorCode.RATE_LIMITED);
        }
        if (enabled && !this.enabled) {
            log.warn(
                    "档案异常访问守卫配置不完整（threshold={} monitored-roles='{}'），本次按未启用处理",
                    threshold,
                    monitoredRolesCsv);
        }
    }

    /**
     * 权限校验通过后的旁路行为检测（由 {@code PermissionAspect} 委派）。
     *
     * <p>只对命中 {@link #TRACKED_PERMISSIONS} 且账号属于 {@link #monitoredRoles} 的调用计数；其余一律直接返回，不产生任何 I/O。
     *
     * @param user 当前登录主体
     * @param grantedPermissions 本次调用所需权限码
     * @throws BizException 仅 {@code ENFORCE} 模式下超阈值时抛出（{@link ErrorCode#RATE_LIMITED}）
     */
    public void onPermissionGranted(LoginUser user, String[] grantedPermissions) {
        if (!enabled
                || user == null
                || grantedPermissions == null
                || grantedPermissions.length == 0) {
            return;
        }
        if (Arrays.stream(grantedPermissions).noneMatch(TRACKED_PERMISSIONS::contains)) {
            return;
        }
        if (monitoredRoles.stream().noneMatch(user::hasAnyRole)) {
            return;
        }
        long windowSeconds = windowMinutes * 60L;
        boolean reject = false;
        try {
            long count =
                    counter.recordAndCount(COUNT_DIMENSION_PREFIX + user.userId(), windowSeconds);
            if (count > threshold) {
                reject = raiseAlert(user, count, windowSeconds);
            }
        } catch (RuntimeException ex) {
            log.warn("档案访问频次统计失败，跳过本次告警判定：userId={}", user.userId(), ex);
        }
        // 拒绝动作置于 try 之外：ENFORCE 是显式选择的处置强度，不能被「失败开放」的兜底捕获吞掉
        if (reject) {
            throw new BizException(ErrorCode.RATE_LIMITED);
        }
    }

    /**
     * 写告警事件 + 按模式通知监管角色（同一窗口内对该账号只告警一次）。
     *
     * @return 是否需要拒绝本次请求（仅 {@code ENFORCE} 模式返回 {@code true}）
     */
    private boolean raiseAlert(LoginUser user, long count, long windowSeconds) {
        if (!counter.tryAcquireOnce(ALERT_DIMENSION_PREFIX + user.userId(), windowSeconds)) {
            return mode.enforces();
        }
        ArchiveAccessAlert alert =
                new ArchiveAccessAlert(
                        user.userId(),
                        user.roles(),
                        count,
                        threshold,
                        windowMinutes,
                        LocalDateTime.now());
        try {
            auditService.record(ALERT_ACTION, ALERT_BIZ_TYPE, false, detailJson(count));
        } catch (RuntimeException ex) {
            // 留痕失败不应连带吞掉监管通知：两条链路各自独立，相互不阻塞
            log.warn("档案异常访问告警写审计失败：userId={}", user.userId(), ex);
        }
        if (mode.notifies()) {
            try {
                notifier.notify(alert);
            } catch (RuntimeException ex) {
                log.warn("档案异常访问告警通知失败：userId={}", user.userId(), ex);
            }
        }
        return mode.enforces();
    }

    /** 告警上下文（全为数值，无需 JSON 转义）。 */
    private String detailJson(long count) {
        return "{\"count\":"
                + count
                + ",\"threshold\":"
                + threshold
                + ",\"windowMinutes\":"
                + windowMinutes
                + "}";
    }

    private static Set<String> parseRoles(String csv) {
        if (csv == null || csv.isBlank()) {
            return Set.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(role -> !role.isEmpty())
                .map(role -> role.toUpperCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
