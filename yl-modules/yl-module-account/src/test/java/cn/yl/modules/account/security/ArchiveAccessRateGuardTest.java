package cn.yl.modules.account.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import cn.yl.api.security.LoginUser;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.common.ratelimit.SlidingWindowCounter;
import cn.yl.modules.account.service.AuditService;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ArchiveAccessRateGuard} 单元测试（CR-M2-001 §2.5）。
 *
 * <p>覆盖四类判定：命中监控角色且超阈值 → 告警；未超阈值 / 非监控角色 / 非档案权限点 → 静默； 同窗口重复调用 → 告警去重；计数设施异常 → 失败开放（不抛错）。
 */
class ArchiveAccessRateGuardTest {

    private static final String ARCHIVE_READ = "elder:archive:read";

    private static final String REPORT_VIEW = "report:view";

    private static LoginUser user(String... roles) {
        return new LoginUser(1001L, 1L, List.of(roles), List.of(ARCHIVE_READ), List.of(1L), 1);
    }

    private static ArchiveAccessRateGuard guard(
            SlidingWindowCounter counter,
            AuditService auditService,
            ArchiveAccessAlertNotifier notifier,
            int threshold,
            boolean enabled,
            String monitoredRoles) {
        return guard(
                counter,
                auditService,
                notifier,
                threshold,
                enabled,
                monitoredRoles,
                ArchiveAccessGuardMode.ALERT.name());
    }

    private static ArchiveAccessRateGuard guard(
            SlidingWindowCounter counter,
            AuditService auditService,
            ArchiveAccessAlertNotifier notifier,
            int threshold,
            boolean enabled,
            String monitoredRoles,
            String mode) {
        return new ArchiveAccessRateGuard(
                enabled, mode, threshold, 60L, monitoredRoles, counter, auditService, notifier);
    }

    @Test
    @DisplayName("命中监控角色且超过阈值：写审计告警 + 通知，且不向调用方抛错")
    void alertsWhenThresholdExceeded() {
        FakeCounter counter = new FakeCounter();
        counter.nextCount = 51L;
        AuditService auditService = mock(AuditService.class);
        CapturingNotifier notifier = new CapturingNotifier();

        guard(counter, auditService, notifier, 50, true, "ELDER,FAMILY,SUPERVISOR")
                .onPermissionGranted(user("FAMILY"), new String[] {ARCHIVE_READ});

        verify(auditService, times(1))
                .record(eq("ARCHIVE_ACCESS_ALERT"), eq("ELDER"), eq(false), anyString());
        assertThat(notifier.alerts).hasSize(1);
        assertThat(notifier.alerts.get(0).count()).isEqualTo(51L);
        assertThat(notifier.alerts.get(0).threshold()).isEqualTo(50);
        assertThat(notifier.alerts.get(0).windowMinutes()).isEqualTo(60L);
    }

    @Test
    @DisplayName("恰好等于阈值不告警（判定为「超过 N 次」而非「达到 N 次」）")
    void doesNotAlertAtExactlyThreshold() {
        FakeCounter counter = new FakeCounter();
        counter.nextCount = 50L;
        CapturingNotifier notifier = new CapturingNotifier();

        guard(counter, mock(AuditService.class), notifier, 50, true, "ELDER,FAMILY,SUPERVISOR")
                .onPermissionGranted(user("ELDER"), new String[] {ARCHIVE_READ});

        assertThat(notifier.alerts).isEmpty();
    }

    @Test
    @DisplayName("护理相关角色（ASSESSOR / ORG_ADMIN）默认不纳入监控，即使超阈值也不告警")
    void skipsCareRoles() {
        FakeCounter counter = new FakeCounter();
        counter.nextCount = 999L;
        CapturingNotifier notifier = new CapturingNotifier();
        ArchiveAccessRateGuard guard =
                guard(
                        counter,
                        mock(AuditService.class),
                        notifier,
                        50,
                        true,
                        "ELDER,FAMILY,SUPERVISOR");

        guard.onPermissionGranted(user("ASSESSOR"), new String[] {ARCHIVE_READ});
        guard.onPermissionGranted(user("ORG_ADMIN"), new String[] {ARCHIVE_READ});

        assertThat(notifier.alerts).isEmpty();
        // 非监控角色连计数都不应发生（避免无意义的 Redis 往返）
        assertThat(counter.recorded).isZero();
    }

    @Test
    @DisplayName("非档案类权限点不计数、不告警")
    void ignoresNonArchivePermissions() {
        FakeCounter counter = new FakeCounter();
        counter.nextCount = 999L;
        CapturingNotifier notifier = new CapturingNotifier();

        guard(counter, mock(AuditService.class), notifier, 50, true, "ELDER,FAMILY,SUPERVISOR")
                .onPermissionGranted(user("FAMILY"), new String[] {REPORT_VIEW});

        assertThat(notifier.alerts).isEmpty();
        assertThat(counter.recorded).isZero();
    }

    @Test
    @DisplayName("同一窗口内重复超阈值只告警一次（去重）")
    void deduplicatesWithinWindow() {
        FakeCounter counter = new FakeCounter();
        counter.nextCount = 60L;
        counter.acquireOnce = true;
        CapturingNotifier notifier = new CapturingNotifier();
        ArchiveAccessRateGuard guard =
                guard(counter, mock(AuditService.class), notifier, 50, true, "SUPERVISOR");

        guard.onPermissionGranted(user("SUPERVISOR"), new String[] {ARCHIVE_READ});
        counter.acquireOnce = false; // 窗口内名额已被占用
        guard.onPermissionGranted(user("SUPERVISOR"), new String[] {ARCHIVE_READ});

        assertThat(notifier.alerts).hasSize(1);
    }

    @Test
    @DisplayName("总开关关闭或阈值非法时按未启用处理：不产生任何计数与告警")
    void inertWhenDisabledOrMisconfigured() {
        FakeCounter counter = new FakeCounter();
        counter.nextCount = 999L;
        CapturingNotifier notifier = new CapturingNotifier();
        AuditService auditService = mock(AuditService.class);

        guard(counter, auditService, notifier, 50, false, "ELDER,FAMILY,SUPERVISOR")
                .onPermissionGranted(user("ELDER"), new String[] {ARCHIVE_READ});
        guard(counter, auditService, notifier, 0, true, "ELDER,FAMILY,SUPERVISOR")
                .onPermissionGranted(user("ELDER"), new String[] {ARCHIVE_READ});
        guard(counter, auditService, notifier, 50, true, "  ")
                .onPermissionGranted(user("ELDER"), new String[] {ARCHIVE_READ});

        assertThat(notifier.alerts).isEmpty();
        assertThat(counter.recorded).isZero();
        verify(auditService, never()).record(anyString(), any(), eq(false), anyString());
    }

    @Test
    @DisplayName("计数设施异常时失败开放：不抛错、不告警，档案读取不受影响")
    void failsOpenOnCounterFailure() {
        SlidingWindowCounter broken =
                new SlidingWindowCounter() {
                    @Override
                    public long recordAndCount(String dimension, long windowSeconds) {
                        throw new IllegalStateException("redis 不可用");
                    }

                    @Override
                    public boolean tryAcquireOnce(String dimension, long windowSeconds) {
                        throw new IllegalStateException("redis 不可用");
                    }
                };
        CapturingNotifier notifier = new CapturingNotifier();

        guard(broken, mock(AuditService.class), notifier, 50, true, "ELDER,FAMILY,SUPERVISOR")
                .onPermissionGranted(user("FAMILY"), new String[] {ARCHIVE_READ});

        assertThat(notifier.alerts).isEmpty();
    }

    @Test
    @DisplayName("mode=off：完全旁路，不计数、不留痕、不通知")
    void inertWhenModeOff() {
        FakeCounter counter = new FakeCounter();
        counter.nextCount = 999L;
        CapturingNotifier notifier = new CapturingNotifier();
        AuditService auditService = mock(AuditService.class);

        guard(counter, auditService, notifier, 50, true, "ELDER", "off")
                .onPermissionGranted(user("ELDER"), new String[] {ARCHIVE_READ});

        assertThat(counter.recorded).isZero();
        assertThat(notifier.alerts).isEmpty();
        verify(auditService, never()).record(anyString(), any(), eq(false), anyString());
    }

    @Test
    @DisplayName("mode=audit：只写审计留痕，不推送监管通知（灰度期）")
    void auditsWithoutNotifying() {
        FakeCounter counter = new FakeCounter();
        counter.nextCount = 80L;
        CapturingNotifier notifier = new CapturingNotifier();
        AuditService auditService = mock(AuditService.class);

        guard(counter, auditService, notifier, 50, true, "FAMILY", "audit")
                .onPermissionGranted(user("FAMILY"), new String[] {ARCHIVE_READ});

        verify(auditService, times(1))
                .record(eq("ARCHIVE_ACCESS_ALERT"), eq("ELDER"), eq(false), anyString());
        assertThat(notifier.alerts).isEmpty();
    }

    @Test
    @DisplayName("mode=enforce：留痕 + 通知后拒绝请求（错误码 10429）")
    void rejectsWhenModeEnforce() {
        FakeCounter counter = new FakeCounter();
        counter.nextCount = 120L;
        CapturingNotifier notifier = new CapturingNotifier();
        AuditService auditService = mock(AuditService.class);

        assertThatThrownBy(
                        () ->
                                guard(
                                                counter,
                                                auditService,
                                                notifier,
                                                50,
                                                true,
                                                "FAMILY",
                                                "enforce")
                                        .onPermissionGranted(
                                                user("FAMILY"), new String[] {ARCHIVE_READ}))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getCode())
                .isEqualTo(ErrorCode.RATE_LIMITED.getCode());

        assertThat(notifier.alerts).hasSize(1);
        verify(auditService, times(1))
                .record(eq("ARCHIVE_ACCESS_ALERT"), eq("ELDER"), eq(false), anyString());
    }

    @Test
    @DisplayName("mode=enforce 且计数设施异常：失败开放，不拒绝、不告警")
    void failsOpenEvenInEnforceMode() {
        SlidingWindowCounter broken =
                new SlidingWindowCounter() {
                    @Override
                    public long recordAndCount(String dimension, long windowSeconds) {
                        throw new IllegalStateException("redis 不可用");
                    }

                    @Override
                    public boolean tryAcquireOnce(String dimension, long windowSeconds) {
                        throw new IllegalStateException("redis 不可用");
                    }
                };
        CapturingNotifier notifier = new CapturingNotifier();

        guard(broken, mock(AuditService.class), notifier, 50, true, "FAMILY", "enforce")
                .onPermissionGranted(user("FAMILY"), new String[] {ARCHIVE_READ});

        assertThat(notifier.alerts).isEmpty();
    }

    @Test
    @DisplayName("未知 mode 回落 alert：配置写错时不静默关掉护栏")
    void fallsBackToAlertOnUnknownMode() {
        FakeCounter counter = new FakeCounter();
        counter.nextCount = 99L;
        CapturingNotifier notifier = new CapturingNotifier();

        guard(counter, mock(AuditService.class), notifier, 50, true, "FAMILY", "draconian")
                .onPermissionGranted(user("FAMILY"), new String[] {ARCHIVE_READ});

        assertThat(notifier.alerts).hasSize(1);
    }

    /** 可编程的计数端口替身。 */
    private static final class FakeCounter implements SlidingWindowCounter {

        private long nextCount = 1L;

        private boolean acquireOnce = true;

        private long recorded;

        @Override
        public long recordAndCount(String dimension, long windowSeconds) {
            recorded++;
            return nextCount;
        }

        @Override
        public boolean tryAcquireOnce(String dimension, long windowSeconds) {
            return acquireOnce;
        }
    }

    /** 捕获告警载荷的通知替身。 */
    private static final class CapturingNotifier implements ArchiveAccessAlertNotifier {

        private final List<ArchiveAccessAlert> alerts = new ArrayList<>();

        @Override
        public void notify(ArchiveAccessAlert alert) {
            alerts.add(alert);
        }
    }
}
