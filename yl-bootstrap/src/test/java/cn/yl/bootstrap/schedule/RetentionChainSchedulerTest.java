package cn.yl.bootstrap.schedule;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yl.modules.account.service.AuditService;
import cn.yl.modules.account.service.retention.ThirdPartyRetentionCleanupTask;
import cn.yl.modules.archive.service.retention.FamilyBindingExpireTask;
import cn.yl.modules.evaluation.service.retention.EvaluationArchiveTask;
import cn.yl.modules.report.service.retention.OrphanFileSweepTask;
import cn.yl.modules.supervise.service.retention.SuperviseReportArchiveTask;
import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * 保留期清理链路测试（ADR-0004）：一次调度做五件事。
 *
 * <p>固定三件事：① 五个动作在同一次调度内、按固定顺序执行；② 只有真的有事发生才写链路级审计（避免每天一条噪声）； ③ <b>触发壳唯一</b>——五个任务类都没有
 * {@code @Scheduled}，防止将来有人"顺手"给任务加注解导致双份调度。
 */
class RetentionChainSchedulerTest {

    @Test
    @DisplayName("一次调度内按固定顺序执行五个动作：三方删除 → 评估单归档 → 监管上报封存 → 亲情绑定流转 → 孤儿文件回收")
    void runsFiveActionsInOrder() {
        ThirdPartyRetentionCleanupTask thirdParty = mock(ThirdPartyRetentionCleanupTask.class);
        EvaluationArchiveTask archive = mock(EvaluationArchiveTask.class);
        SuperviseReportArchiveTask supervise = mock(SuperviseReportArchiveTask.class);
        FamilyBindingExpireTask familyBind = mock(FamilyBindingExpireTask.class);
        OrphanFileSweepTask orphan = mock(OrphanFileSweepTask.class);
        when(thirdParty.purgeExpiredBindings()).thenReturn(3);
        when(archive.archiveExpiredOrders()).thenReturn(7);
        when(supervise.archiveExpiredReports()).thenReturn(5);
        when(familyBind.expireExpiredBindings()).thenReturn(9);
        when(orphan.sweepOrphanFiles()).thenReturn(4);

        scheduler(thirdParty, archive, supervise, familyBind, orphan, mock(AuditService.class))
                .runRetentionChain();

        InOrder order = inOrder(thirdParty, archive, supervise, familyBind, orphan);
        order.verify(thirdParty).purgeExpiredBindings();
        order.verify(archive).archiveExpiredOrders();
        order.verify(supervise).archiveExpiredReports();
        order.verify(familyBind).expireExpiredBindings();
        order.verify(orphan).sweepOrphanFiles();
    }

    @Test
    @DisplayName("五个动作都有结果时写一条链路级审计，明细含五个计数")
    void recordsSingleAuditWithFiveCounts() {
        ThirdPartyRetentionCleanupTask thirdParty = mock(ThirdPartyRetentionCleanupTask.class);
        EvaluationArchiveTask archive = mock(EvaluationArchiveTask.class);
        SuperviseReportArchiveTask supervise = mock(SuperviseReportArchiveTask.class);
        FamilyBindingExpireTask familyBind = mock(FamilyBindingExpireTask.class);
        OrphanFileSweepTask orphan = mock(OrphanFileSweepTask.class);
        AuditService audit = mock(AuditService.class);
        when(thirdParty.purgeExpiredBindings()).thenReturn(3);
        when(archive.archiveExpiredOrders()).thenReturn(7);
        when(supervise.archiveExpiredReports()).thenReturn(5);
        when(familyBind.expireExpiredBindings()).thenReturn(9);
        when(orphan.sweepOrphanFiles()).thenReturn(4);

        scheduler(thirdParty, archive, supervise, familyBind, orphan, audit).runRetentionChain();

        verify(audit, times(1))
                .record(
                        eq("RETENTION_CHAIN"),
                        eq("retention"),
                        eq(false),
                        contains("\"purged\":3"));
        verify(audit, times(1))
                .record(anyString(), anyString(), anyBoolean(), contains("\"archived\":7"));
        verify(audit, times(1))
                .record(anyString(), anyString(), anyBoolean(), contains("\"archivedReports\":5"));
        verify(audit, times(1))
                .record(anyString(), anyString(), anyBoolean(), contains("\"expiredBindings\":9"));
        verify(audit, times(1))
                .record(anyString(), anyString(), anyBoolean(), contains("\"swept\":4"));
    }

    @Test
    @DisplayName("五件事都没做到事：不写审计（避免每天一条无意义噪声）")
    void skipsAuditWhenNothingHappened() {
        ThirdPartyRetentionCleanupTask thirdParty = mock(ThirdPartyRetentionCleanupTask.class);
        EvaluationArchiveTask archive = mock(EvaluationArchiveTask.class);
        SuperviseReportArchiveTask supervise = mock(SuperviseReportArchiveTask.class);
        FamilyBindingExpireTask familyBind = mock(FamilyBindingExpireTask.class);
        OrphanFileSweepTask orphan = mock(OrphanFileSweepTask.class);
        AuditService audit = mock(AuditService.class);
        when(thirdParty.purgeExpiredBindings()).thenReturn(0);
        when(archive.archiveExpiredOrders()).thenReturn(0);
        when(supervise.archiveExpiredReports()).thenReturn(0);
        when(familyBind.expireExpiredBindings()).thenReturn(0);
        when(orphan.sweepOrphanFiles()).thenReturn(0);

        scheduler(thirdParty, archive, supervise, familyBind, orphan, audit).runRetentionChain();

        verify(audit, never()).record(anyString(), any(), anyBoolean(), any());
    }

    @Test
    @DisplayName("仅第四环（亲情绑定流转）有事发生：仍写审计（门槛为五者不同时为 0）")
    void writesAuditWhenOnlyFourthActionHappened() {
        ThirdPartyRetentionCleanupTask thirdParty = mock(ThirdPartyRetentionCleanupTask.class);
        EvaluationArchiveTask archive = mock(EvaluationArchiveTask.class);
        SuperviseReportArchiveTask supervise = mock(SuperviseReportArchiveTask.class);
        FamilyBindingExpireTask familyBind = mock(FamilyBindingExpireTask.class);
        OrphanFileSweepTask orphan = mock(OrphanFileSweepTask.class);
        AuditService audit = mock(AuditService.class);
        when(thirdParty.purgeExpiredBindings()).thenReturn(0);
        when(archive.archiveExpiredOrders()).thenReturn(0);
        when(supervise.archiveExpiredReports()).thenReturn(0);
        when(familyBind.expireExpiredBindings()).thenReturn(2);
        when(orphan.sweepOrphanFiles()).thenReturn(0);

        scheduler(thirdParty, archive, supervise, familyBind, orphan, audit).runRetentionChain();

        verify(audit, times(1))
                .record(anyString(), anyString(), anyBoolean(), contains("\"expiredBindings\":2"));
    }

    @Test
    @DisplayName("★仅第五环（孤儿文件回收）有事发生：仍写审计（门槛为五者不同时为 0）")
    void writesAuditWhenOnlyFifthActionHappened() {
        ThirdPartyRetentionCleanupTask thirdParty = mock(ThirdPartyRetentionCleanupTask.class);
        EvaluationArchiveTask archive = mock(EvaluationArchiveTask.class);
        SuperviseReportArchiveTask supervise = mock(SuperviseReportArchiveTask.class);
        FamilyBindingExpireTask familyBind = mock(FamilyBindingExpireTask.class);
        OrphanFileSweepTask orphan = mock(OrphanFileSweepTask.class);
        AuditService audit = mock(AuditService.class);
        when(thirdParty.purgeExpiredBindings()).thenReturn(0);
        when(archive.archiveExpiredOrders()).thenReturn(0);
        when(supervise.archiveExpiredReports()).thenReturn(0);
        when(familyBind.expireExpiredBindings()).thenReturn(0);
        when(orphan.sweepOrphanFiles()).thenReturn(6);

        scheduler(thirdParty, archive, supervise, familyBind, orphan, audit).runRetentionChain();

        verify(audit, times(1))
                .record(anyString(), anyString(), anyBoolean(), contains("\"swept\":6"));
    }

    @Test
    @DisplayName("审计留痕失败不影响链路结果：不抛出")
    void auditFailureDoesNotBreakChain() {
        ThirdPartyRetentionCleanupTask thirdParty = mock(ThirdPartyRetentionCleanupTask.class);
        EvaluationArchiveTask archive = mock(EvaluationArchiveTask.class);
        SuperviseReportArchiveTask supervise = mock(SuperviseReportArchiveTask.class);
        FamilyBindingExpireTask familyBind = mock(FamilyBindingExpireTask.class);
        OrphanFileSweepTask orphan = mock(OrphanFileSweepTask.class);
        AuditService audit = mock(AuditService.class);
        when(thirdParty.purgeExpiredBindings()).thenReturn(1);
        when(archive.archiveExpiredOrders()).thenReturn(0);
        when(supervise.archiveExpiredReports()).thenReturn(0);
        when(familyBind.expireExpiredBindings()).thenReturn(0);
        when(orphan.sweepOrphanFiles()).thenReturn(0);
        doThrow(new IllegalStateException("audit down"))
                .when(audit)
                .record(anyString(), anyString(), anyBoolean(), anyString());

        assertThatCode(
                        () ->
                                scheduler(thirdParty, archive, supervise, familyBind, orphan, audit)
                                        .runRetentionChain())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("触发壳唯一：链路方法带 @Scheduled，五个任务方法都不带（防双份调度）")
    void schedulerIsTheOnlyTrigger() throws Exception {
        Method chain = RetentionChainScheduler.class.getMethod("runRetentionChain");
        assertThat(chain.getAnnotation(Scheduled.class)).as("链路必须有且仅有这一个触发壳").isNotNull();

        Method purge = ThirdPartyRetentionCleanupTask.class.getMethod("purgeExpiredBindings");
        assertThat(purge.getAnnotation(Scheduled.class))
                .as("ER-14 清理不应自带 @Scheduled（触发权已上移链路）")
                .isNull();

        Method archive = EvaluationArchiveTask.class.getMethod("archiveExpiredOrders");
        assertThat(archive.getAnnotation(Scheduled.class))
                .as("ER-03 评估单归档不应自带 @Scheduled（触发权已上移链路）")
                .isNull();

        Method superviseArchive =
                SuperviseReportArchiveTask.class.getMethod("archiveExpiredReports");
        assertThat(superviseArchive.getAnnotation(Scheduled.class))
                .as("ER-03 监管上报归档不应自带 @Scheduled（触发权已上移链路）")
                .isNull();

        Method familyExpire = FamilyBindingExpireTask.class.getMethod("expireExpiredBindings");
        assertThat(familyExpire.getAnnotation(Scheduled.class))
                .as("D4-R1 亲情绑定流转不应自带 @Scheduled（触发权已上移链路）")
                .isNull();

        Method orphanSweep = OrphanFileSweepTask.class.getMethod("sweepOrphanFiles");
        assertThat(orphanSweep.getAnnotation(Scheduled.class))
                .as("P2-7 孤儿文件回收不应自带 @Scheduled（触发权已上移链路）")
                .isNull();
    }

    private static RetentionChainScheduler scheduler(
            ThirdPartyRetentionCleanupTask thirdParty,
            EvaluationArchiveTask archive,
            SuperviseReportArchiveTask supervise,
            FamilyBindingExpireTask familyBind,
            OrphanFileSweepTask orphan,
            AuditService audit) {
        return new RetentionChainScheduler(
                thirdParty, archive, supervise, familyBind, orphan, audit);
    }
}
