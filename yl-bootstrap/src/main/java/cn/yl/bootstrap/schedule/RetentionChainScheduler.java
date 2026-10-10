package cn.yl.bootstrap.schedule;

import cn.yl.modules.account.service.AuditService;
import cn.yl.modules.account.service.retention.ThirdPartyRetentionCleanupTask;
import cn.yl.modules.archive.service.retention.FamilyBindingExpireTask;
import cn.yl.modules.evaluation.service.retention.EvaluationArchiveTask;
import cn.yl.modules.report.service.retention.OrphanFileSweepTask;
import cn.yl.modules.supervise.service.retention.SuperviseReportArchiveTask;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 保留期清理链路（ADR-0004 调度框架的唯一触发壳）：一次调度做五件事。
 *
 * <ol>
 *   <li><b>ER-14</b> 三方账号绑定到期后<b>物理删除</b>（合规：注销后保留 30 天，超期不得继续留存）；
 *   <li><b>ER-03</b> 评估单超保留期后转 {@code ARCHIVED} <b>只读封存</b>（5 年留存对象，只脱业务视图、不销毁）；
 *   <li><b>ER-03</b> 监管上报记录超保留期后写 {@code archived_at} <b>只读封存</b>（对外凭证，不销毁；D-5 裁决 D5-D1）；
 *   <li><b>D4-R1</b> 亲情绑定授权到期后 {@code status 2 → 4} <b>状态流转</b>（只改状态、不碰 {@code deleted}；D-5 裁决
 *       D5-D3）；
 *   <li><b>P2-7</b> 存储根内的<b>孤儿文件</b>移入隔离目录 {@code .trash/}（不 {@code rm}、可还原；先置到期导出任务 {@code status
 *       = 4} 再回收，用户裁决 D1-B）。
 * </ol>
 *
 * <h3>为什么壳在这里（而不是各任务自己 {@code @Scheduled}）</h3>
 *
 * <p>五个动作分属 {@code yl-module-account} / {@code yl-module-evaluation} / {@code yl-module-supervise}
 * / {@code yl-module-archive} / {@code yl-module-report}，五者是<b>平级兄弟模块</b>（均为 {@code yl-api}
 * 的下游、互不依赖），任一模块都无法引用对方的任务类。 {@code yl-bootstrap}
 * 是唯一同时依赖各模块的模块，因此链路编排只能落在此处——这也让「一次调度、一次链路级留痕、一份总数」成为可能， 而不是五条互不知情的定时任务。
 *
 * <h3>约束（承 ADR-0004）</h3>
 *
 * <ul>
 *   <li><b>不加分布式锁</b>：五个动作都幂等（删除按 {@code retain_until < now}；评估单归档按 {@code status='PUBLISHED'}
 *       ；监管上报 归档按 {@code archived_at IS NULL}；亲情绑定流转按 {@code status =
 *       2}；孤儿回收「命中即移出根」），多实例并发最坏是重复空跑；
 *   <li><b>顺序与隔离</b>：五个任务各自吞掉自身异常并返回 0，故<b>前一环失败不影响后四环</b>；链路级异常也仅记 error， 不向调度器抛出（下一周期自然重试）；
 *   <li><b>留痕</b>：链路级审计只在「有事发生」时写一条（五个计数均为 0 则不写），避免每天一条无意义噪声。
 * </ul>
 *
 * <p>配置：{@code yl.retention.chain-cron}（默认每天 03:15）、{@code yl.retention.chain.enabled}。 各任务自身的
 * {@code batch-size} / {@code max-rounds} / {@code dry-run} 见 {@code
 * yl.retention.third-party.*}、{@code yl.retention.eval-archive.*}、{@code
 * yl.retention.supervise-archive.*}、{@code yl.retention.family-bind-expire.*} 与 {@code
 * yl.retention.orphan-file.*}。
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "yl.retention.chain",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RetentionChainScheduler {

    private static final String LOG_PREFIX = "[retention]";

    private static final String AUDIT_ACTION = "RETENTION_CHAIN";

    private static final String AUDIT_BIZ_TYPE = "retention";

    private final ThirdPartyRetentionCleanupTask thirdPartyTask;

    private final EvaluationArchiveTask archiveTask;

    private final SuperviseReportArchiveTask superviseArchiveTask;

    private final FamilyBindingExpireTask familyBindExpireTask;

    private final OrphanFileSweepTask orphanFileSweepTask;

    private final AuditService auditService;

    public RetentionChainScheduler(
            ThirdPartyRetentionCleanupTask thirdPartyTask,
            EvaluationArchiveTask archiveTask,
            SuperviseReportArchiveTask superviseArchiveTask,
            FamilyBindingExpireTask familyBindExpireTask,
            OrphanFileSweepTask orphanFileSweepTask,
            AuditService auditService) {
        this.thirdPartyTask = thirdPartyTask;
        this.archiveTask = archiveTask;
        this.superviseArchiveTask = superviseArchiveTask;
        this.familyBindExpireTask = familyBindExpireTask;
        this.orphanFileSweepTask = orphanFileSweepTask;
        this.auditService = auditService;
    }

    /**
     * 保留期清理链路入口（触发壳）。默认每天凌晨 03:15 触发。
     *
     * <p>迁移到 XXL-Job 时只需把本注解替换为 {@code @XxlJob("retentionChain")}，五个任务的业务方法体不动。
     */
    @Scheduled(cron = "${yl.retention.chain-cron:0 15 3 * * *}")
    public void runRetentionChain() {
        long startedAt = System.currentTimeMillis();
        int purged = thirdPartyTask.purgeExpiredBindings();
        int archived = archiveTask.archiveExpiredOrders();
        int archivedReports = superviseArchiveTask.archiveExpiredReports();
        int expiredBindings = familyBindExpireTask.expireExpiredBindings();
        int swept = orphanFileSweepTask.sweepOrphanFiles();
        long costMs = System.currentTimeMillis() - startedAt;
        log.info(
                "{} 链路完成：三方绑定物理删除 {} 条、超期评估单归档 {} 单、超期监管上报封存 {} 条、到期亲情绑定流转 {} 条、"
                        + "孤儿文件回收 {} 个，耗时 {} ms",
                LOG_PREFIX,
                purged,
                archived,
                archivedReports,
                expiredBindings,
                swept,
                costMs);
        if (purged == 0
                && archived == 0
                && archivedReports == 0
                && expiredBindings == 0
                && swept == 0) {
            return;
        }
        recordAudit(purged, archived, archivedReports, expiredBindings, swept, costMs);
    }

    /** 链路级留痕；留痕失败不影响已完成的清理，只记 error。 */
    private void recordAudit(
            int purged,
            int archived,
            int archivedReports,
            int expiredBindings,
            int swept,
            long costMs) {
        String detail =
                String.format(
                        "{\"purged\":%d,\"archived\":%d,\"archivedReports\":%d,\"expiredBindings\":%d,"
                                + "\"swept\":%d,\"costMs\":%d}",
                        purged, archived, archivedReports, expiredBindings, swept, costMs);
        try {
            auditService.record(AUDIT_ACTION, AUDIT_BIZ_TYPE, false, detail);
        } catch (RuntimeException ex) {
            log.error(
                    "{} 链路已完成但审计留痕失败：purged={} archived={} archivedReports={} expiredBindings={}"
                            + " swept={}",
                    LOG_PREFIX,
                    purged,
                    archived,
                    archivedReports,
                    expiredBindings,
                    swept,
                    ex);
        }
    }
}
