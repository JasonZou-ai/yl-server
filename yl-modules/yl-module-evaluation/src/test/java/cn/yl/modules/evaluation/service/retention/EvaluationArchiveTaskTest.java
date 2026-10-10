package cn.yl.modules.evaluation.service.retention;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.yl.modules.evaluation.domain.archive.ExpiredOrderArchiver;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 评估单超期归档任务单测（ER-03 / ARCHIVED）。
 *
 * <p>端口用可编程替身，不依赖数据库与 Spring 容器；重点固定「限批 + 最大轮次 + 幂等空跑 + 失败不外抛」四条约束 （承 ADR-0004，与 ER-14
 * 清理任务同构），以及<b>归档是状态流转而非删除</b>的返回值口径。
 */
class EvaluationArchiveTaskTest {

    @Test
    @DisplayName("无到期数据：只跑一批即停，返回 0")
    void noExpiredRows() {
        FakeArchiver archiver = new FakeArchiver(0);

        int archived = task(archiver, 500, 20, false).archiveExpiredOrders();

        assertThat(archiver.calls).isEqualTo(1);
        assertThat(archived).isZero();
    }

    @Test
    @DisplayName("归不满一批：单轮结束，返回实际归档数")
    void partialBatch() {
        FakeArchiver archiver = new FakeArchiver(300);

        int archived = task(archiver, 500, 20, false).archiveExpiredOrders();

        assertThat(archiver.calls).isEqualTo(1);
        assertThat(archived).isEqualTo(300);
    }

    @Test
    @DisplayName("连续归满：多轮循环直到某轮不满")
    void multipleRounds() {
        FakeArchiver archiver = new FakeArchiver(500, 500, 200);

        int archived = task(archiver, 500, 20, false).archiveExpiredOrders();

        assertThat(archiver.calls).isEqualTo(3);
        assertThat(archived).isEqualTo(1200);
    }

    @Test
    @DisplayName("达到最大轮次：停止并保留已归量，不无限循环")
    void stopsAtMaxRounds() {
        FakeArchiver archiver = new FakeArchiver(500, 500, 500, 500);

        int archived = task(archiver, 500, 2, false).archiveExpiredOrders();

        assertThat(archiver.calls).isEqualTo(2);
        assertThat(archived).isEqualTo(1000);
    }

    @Test
    @DisplayName("dry-run：只统计不归档")
    void dryRunDoesNotArchive() {
        FakeArchiver archiver = new FakeArchiver(500);

        int archived = task(archiver, 500, 20, true).archiveExpiredOrders();

        assertThat(archiver.archiveCalls).isZero();
        assertThat(archiver.countCalls).isEqualTo(1);
        assertThat(archived).isZero();
    }

    @Test
    @DisplayName("归档端口异常：吞掉不抛出（否则会拖垮同链路的三方绑定清理），等待下周期重试")
    void swallowsArchiverFailure() {
        ExpiredOrderArchiver archiver = mock(ExpiredOrderArchiver.class);
        when(archiver.archiveExpired(any(), anyInt()))
                .thenThrow(new IllegalStateException("db down"));

        EvaluationArchiveTask task = task(archiver, 500, 20, false);

        assertThatCode(task::archiveExpiredOrders).doesNotThrowAnyException();
        assertThat(task.archiveExpiredOrders()).isZero();
    }

    /** 触发壳守卫：本任务不得自带 {@code @Scheduled}（触发权归 yl-bootstrap 的链路壳）。 */
    @Test
    @DisplayName("无 @Scheduled 注解：触发壳统一在保留期链路，避免两处各自调度")
    void hasNoScheduledTrigger() throws Exception {
        assertThat(
                        EvaluationArchiveTask.class
                                .getMethod("archiveExpiredOrders")
                                .getAnnotations()
                                .length)
                .as("归档任务不应自带 @Scheduled，触发壳见 RetentionChainScheduler")
                .isZero();
    }

    private static EvaluationArchiveTask task(
            ExpiredOrderArchiver archiver, int batchSize, int maxRounds, boolean dryRun) {
        return new EvaluationArchiveTask(archiver, batchSize, maxRounds, dryRun);
    }

    /** 可编程替身：按给定序列返回归档行数，用尽后返回 0。 */
    private static final class FakeArchiver implements ExpiredOrderArchiver {

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
