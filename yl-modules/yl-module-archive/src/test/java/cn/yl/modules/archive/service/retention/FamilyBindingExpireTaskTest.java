package cn.yl.modules.archive.service.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.yl.modules.archive.domain.retention.ExpiredFamilyBindingExpirer;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 亲情绑定到期流转任务单测（D4-R1 / {@code status 2 → 4}，D-5 裁决 D5-D3）。
 *
 * <p>端口用可编程替身，不依赖数据库与 Spring 容器；重点固定「限批 + 最大轮次 + 幂等空跑 + 失败不外抛」四条约束（承 ADR-0004，与
 * 其余保留期任务同构），以及<b>流转是状态变更而非删除</b>的返回值口径。
 */
class FamilyBindingExpireTaskTest {

    @Test
    @DisplayName("无到期数据：只跑一批即停，返回 0")
    void noExpiredRows() {
        FakeExpirer expirer = new FakeExpirer(0);

        int expired = task(expirer, 500, 20, false).expireExpiredBindings();

        assertThat(expirer.calls).isEqualTo(1);
        assertThat(expired).isZero();
    }

    @Test
    @DisplayName("转不满一批：单轮结束，返回实际流转数")
    void partialBatch() {
        FakeExpirer expirer = new FakeExpirer(300);

        int expired = task(expirer, 500, 20, false).expireExpiredBindings();

        assertThat(expirer.calls).isEqualTo(1);
        assertThat(expired).isEqualTo(300);
    }

    @Test
    @DisplayName("连续转满：多轮循环直到某轮不满")
    void multipleRounds() {
        FakeExpirer expirer = new FakeExpirer(500, 500, 200);

        int expired = task(expirer, 500, 20, false).expireExpiredBindings();

        assertThat(expirer.calls).isEqualTo(3);
        assertThat(expired).isEqualTo(1200);
    }

    @Test
    @DisplayName("达到最大轮次：停止并保留已转量，不无限循环")
    void stopsAtMaxRounds() {
        FakeExpirer expirer = new FakeExpirer(500, 500, 500, 500);

        int expired = task(expirer, 500, 2, false).expireExpiredBindings();

        assertThat(expirer.calls).isEqualTo(2);
        assertThat(expired).isEqualTo(1000);
    }

    @Test
    @DisplayName("dry-run：只统计不流转")
    void dryRunDoesNotExpire() {
        FakeExpirer expirer = new FakeExpirer(500);

        int expired = task(expirer, 500, 20, true).expireExpiredBindings();

        assertThat(expirer.expireCalls).isZero();
        assertThat(expirer.countCalls).isEqualTo(1);
        assertThat(expired).isZero();
    }

    @Test
    @DisplayName("流转端口异常：吞掉不抛出（否则会拖垮同链路其余动作），等待下周期重试")
    void swallowsExpirerFailure() {
        ExpiredFamilyBindingExpirer expirer = mock(ExpiredFamilyBindingExpirer.class);
        when(expirer.expireExpired(any(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        FamilyBindingExpireTask task = task(expirer, 500, 20, false);

        assertThatCode(task::expireExpiredBindings).doesNotThrowAnyException();
        assertThat(task.expireExpiredBindings()).isZero();
    }

    /** 触发壳守卫：本任务不得自带 {@code @Scheduled}（触发权归 yl-bootstrap 的链路壳）。 */
    @Test
    @DisplayName("无 @Scheduled 注解：触发壳统一在保留期链路，避免两处各自调度")
    void hasNoScheduledTrigger() throws Exception {
        assertThat(
                        FamilyBindingExpireTask.class
                                .getMethod("expireExpiredBindings")
                                .getAnnotations()
                                .length)
                .as("亲情绑定到期流转任务不应自带 @Scheduled，触发壳见 RetentionChainScheduler")
                .isZero();
    }

    private static FamilyBindingExpireTask task(
            ExpiredFamilyBindingExpirer expirer, int batchSize, int maxRounds, boolean dryRun) {
        return new FamilyBindingExpireTask(expirer, batchSize, maxRounds, dryRun);
    }

    /** 可编程替身：按给定序列返回流转行数，用尽后返回 0。 */
    private static final class FakeExpirer implements ExpiredFamilyBindingExpirer {

        private final Deque<Integer> counts = new ArrayDeque<>();

        private int calls;

        private int expireCalls;

        private int countCalls;

        private FakeExpirer(Integer... counts) {
            for (Integer c : counts) {
                this.counts.add(c);
            }
        }

        @Override
        public int expireExpired(LocalDateTime cutoff, int limit) {
            expireCalls++;
            calls++;
            Integer next = counts.poll();
            return next == null ? 0 : next;
        }

        @Override
        public long countExpired(LocalDateTime cutoff) {
            countCalls++;
            return 42L;
        }
    }
}
