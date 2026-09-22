package cn.yl.modules.account.service.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yl.modules.account.domain.thirdparty.ExpiredBindingPurger;
import cn.yl.modules.account.service.AuditService;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 留存到期清理任务单测（ER-14）。端口用可编程替身，不依赖数据库与 Spring 容器。 */
class ThirdPartyRetentionCleanupTaskTest {

    @Test
    @DisplayName("无到期数据：只跑一批即停，不写审计留痕")
    void noExpiredRows() {
        FakePurger purger = new FakePurger(0);
        AuditService audit = mock(AuditService.class);

        task(purger, audit, 500, 20, false).purgeExpiredBindings();

        assertThat(purger.calls).isEqualTo(1);
        verify(audit, never()).record(anyString(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("删不满一批：单轮结束，写一次审计留痕")
    void partialBatch() {
        FakePurger purger = new FakePurger(300);
        AuditService audit = mock(AuditService.class);

        task(purger, audit, 500, 20, false).purgeExpiredBindings();

        assertThat(purger.calls).isEqualTo(1);
        verify(audit, times(1))
                .record(eq("THIRD_PARTY_PURGE"), eq("third_party"), eq(false), any());
    }

    @Test
    @DisplayName("连续删满：多轮循环直到某轮删不满")
    void multipleRounds() {
        FakePurger purger = new FakePurger(500, 500, 200);
        AuditService audit = mock(AuditService.class);

        task(purger, audit, 500, 20, false).purgeExpiredBindings();

        assertThat(purger.calls).isEqualTo(3);
        // 多次删除只留痕一条，不留痕与批次一一对应
        verify(audit, times(1))
                .record(eq("THIRD_PARTY_PURGE"), eq("third_party"), eq(false), any());
    }

    @Test
    @DisplayName("达到最大轮次：停止并保留已删除量，不无限循环")
    void stopsAtMaxRounds() {
        FakePurger purger = new FakePurger(500, 500, 500, 500);
        AuditService audit = mock(AuditService.class);

        task(purger, audit, 500, 2, false).purgeExpiredBindings();

        assertThat(purger.calls).isEqualTo(2);
        verify(audit, times(1)).record(anyString(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("dry-run：只统计不删除、不写审计")
    void dryRunDoesNotDelete() {
        FakePurger purger = new FakePurger(500);
        AuditService audit = mock(AuditService.class);

        task(purger, audit, 500, 20, true).purgeExpiredBindings();

        assertThat(purger.purgeCalls).isZero();
        assertThat(purger.countCalls).isEqualTo(1);
        verify(audit, never()).record(anyString(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("清理端口异常：吞掉不抛出，等待下个周期重试（幂等，重试安全）")
    void swallowsPurgerFailure() {
        ExpiredBindingPurger purger = mock(ExpiredBindingPurger.class);
        when(purger.purgeExpired(any(), anyInt())).thenThrow(new IllegalStateException("db down"));
        AuditService audit = mock(AuditService.class);

        ThirdPartyRetentionCleanupTask task = task(purger, audit, 500, 20, false);

        assertThatCode(task::purgeExpiredBindings).doesNotThrowAnyException();
        verify(audit, never()).record(anyString(), any(), anyBoolean(), any());
    }

    private static ThirdPartyRetentionCleanupTask task(
            ExpiredBindingPurger purger,
            AuditService audit,
            int batchSize,
            int maxRounds,
            boolean dryRun) {
        return new ThirdPartyRetentionCleanupTask(purger, audit, batchSize, maxRounds, dryRun);
    }

    /** 可编程替身：按给定序列返回删除行数，用尽后返回 0。 */
    private static final class FakePurger implements ExpiredBindingPurger {

        private final Deque<Integer> counts = new ArrayDeque<>();

        private int calls;

        private int purgeCalls;

        private int countCalls;

        private FakePurger(Integer... counts) {
            for (Integer c : counts) {
                this.counts.add(c);
            }
        }

        @Override
        public int purgeExpired(LocalDateTime cutoff, int limit) {
            purgeCalls++;
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
