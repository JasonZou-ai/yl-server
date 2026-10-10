package cn.yl.modules.supervise.service.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.yl.modules.supervise.domain.retention.ExpiredSuperviseReportArchiver;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 监管上报超期归档任务单测（ER-03 / {@code archived_at}，D-5 裁决 D5-D1）。
 *
 * <p>端口用可编程替身，不依赖数据库与 Spring 容器；重点固定「限批 + 最大轮次 + 幂等空跑 + 失败不外抛」四条约束（承 ADR-0004，与 ER-03
 * 评估单归档任务同构），以及<b>归档是只读封存而非删除</b>的返回值口径。
 */
class SuperviseReportArchiveTaskTest {

    @Test
    @DisplayName("无到期数据：只跑一批即停，返回 0")
    void noExpiredRows() {
        FakeArchiver archiver = new FakeArchiver(0);

        int archived = task(archiver, 500, 20, false).archiveExpiredReports();

        assertThat(archiver.calls).isEqualTo(1);
        assertThat(archived).isZero();
    }

    @Test
    @DisplayName("归不满一批：单轮结束，返回实际归档数")
    void partialBatch() {
        FakeArchiver archiver = new FakeArchiver(300);

        int archived = task(archiver, 500, 20, false).archiveExpiredReports();

        assertThat(archiver.calls).isEqualTo(1);
        assertThat(archived).isEqualTo(300);
    }

    @Test
    @DisplayName("连续归满：多轮循环直到某轮不满")
    void multipleRounds() {
        FakeArchiver archiver = new FakeArchiver(500, 500, 200);

        int archived = task(archiver, 500, 20, false).archiveExpiredReports();

        assertThat(archiver.calls).isEqualTo(3);
        assertThat(archived).isEqualTo(1200);
    }

    @Test
    @DisplayName("达到最大轮次：停止并保留已归量，不无限循环")
    void stopsAtMaxRounds() {
        FakeArchiver archiver = new FakeArchiver(500, 500, 500, 500);

        int archived = task(archiver, 500, 2, false).archiveExpiredReports();

        assertThat(archiver.calls).isEqualTo(2);
        assertThat(archived).isEqualTo(1000);
    }

    @Test
    @DisplayName("dry-run：只统计不归档")
    void dryRunDoesNotArchive() {
        FakeArchiver archiver = new FakeArchiver(500);

        int archived = task(archiver, 500, 20, true).archiveExpiredReports();

        assertThat(archiver.archiveCalls).isZero();
        assertThat(archiver.countCalls).isEqualTo(1);
        assertThat(archived).isZero();
    }

    @Test
    @DisplayName("归档端口异常：吞掉不抛出（否则会拖垮同链路其余动作），等待下周期重试")
    void swallowsArchiverFailure() {
        ExpiredSuperviseReportArchiver archiver = mock(ExpiredSuperviseReportArchiver.class);
        when(archiver.archiveExpired(any(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        SuperviseReportArchiveTask task = task(archiver, 500, 20, false);

        assertThatCode(task::archiveExpiredReports).doesNotThrowAnyException();
        assertThat(task.archiveExpiredReports()).isZero();
    }

    /** 触发壳守卫：本任务不得自带 {@code @Scheduled}（触发权归 yl-bootstrap 的链路壳）。 */
    @Test
    @DisplayName("无 @Scheduled 注解：触发壳统一在保留期链路，避免两处各自调度")
    void hasNoScheduledTrigger() throws Exception {
        assertThat(
                        SuperviseReportArchiveTask.class
                                .getMethod("archiveExpiredReports")
                                .getAnnotations()
                                .length)
                .as("监管上报归档任务不应自带 @Scheduled，触发壳见 RetentionChainScheduler")
                .isZero();
    }

    private static SuperviseReportArchiveTask task(
            ExpiredSuperviseReportArchiver archiver, int batchSize, int maxRounds, boolean dryRun) {
        return new SuperviseReportArchiveTask(archiver, batchSize, maxRounds, dryRun);
    }

    /** 可编程替身：按给定序列返回归档行数，用尽后返回 0。 */
    private static final class FakeArchiver implements ExpiredSuperviseReportArchiver {

        private final Deque<Integer> counts = new ArrayDeque<>();

        private int calls;

        private int archiveCalls;

        private int countCalls;

        private FakeArchiver(Integer... counts) {
            for (Integer c : counts) {
                this.counts.add(c);
            }
        }

        @Override
        public int archiveExpired(LocalDateTime cutoff, int limit) {
            archiveCalls++;
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
