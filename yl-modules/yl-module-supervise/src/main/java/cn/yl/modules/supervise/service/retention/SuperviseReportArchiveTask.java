package cn.yl.modules.supervise.service.retention;

import cn.yl.modules.supervise.domain.retention.ExpiredSuperviseReportArchiver;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 监管上报记录超保留期归档任务（ER-03 {@code retain_until} · {@code archived_at} 只读封存落地，D-5 裁决 D5-D1）。
 *
 * <p>监管上报记录在保留期（{@code retain_until}，B10 写入口径 = now + 5 年）到期后写入 {@code archived_at}：<b>只读封存、
 * 不物理删除</b>。监管上报是对外凭证，属 5 年留存对象，到期只是脱离日常业务视图，不是销毁。
 *
 * <h3>调度选型与约束</h3>
 *
 * <p>沿用 ADR-0004 的三条硬约束（本任务<b>没有自己的 {@code @Scheduled}</b>，触发壳上移到链路编排）：
 *
 * <ol>
 *   <li><b>不加分布式锁</b>：归档条件含 {@code archived_at IS NULL}，封存后即离开命中集合，动作幂等；
 *   <li><b>限批执行</b>：{@code batch-size}（默认 500）+ {@code max-rounds}（默认 20），避免大表长事务；
 *   <li><b>失败可重试</b>：异常只记 error、不向调度器抛出，下一周期自然重试（幂等，重试安全）。
 * </ol>
 *
 * <h3>为什么触发壳在 yl-bootstrap</h3>
 *
 * <p>本任务与 ER-14 三方绑定清理、ER-03 评估单归档分属 supervise / account / evaluation 三个<b>平级兄弟模块</b>（互不依赖，只共同 依赖
 * {@code yl-api}），任一模块都无法编排对方。故按「一个定时清理链路、一次做四件事」的裁决，由唯一能同时看见各模块的 {@code yl-bootstrap} 提供触发壳 {@code
 * RetentionChainScheduler}：业务动作留在本类的普通方法中，迁移到 XXL-Job 时只需替换链路壳上的注解 （ADR-0004「迁移边界」）。
 *
 * <p>配置（{@code yl.retention.supervise-archive.*}）：{@code enabled} / {@code batch-size} / {@code
 * max-rounds} / {@code dry-run}。{@code dry-run=true} 时只统计不归档，用于上线前核对影响面。
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "yl.retention.supervise-archive",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class SuperviseReportArchiveTask {

    private static final String LOG_PREFIX = "[retention]";

    private final ExpiredSuperviseReportArchiver archiver;

    private final int batchSize;

    private final int maxRounds;

    private final boolean dryRun;

    /**
     * @param archiver 归档端口（只读封存，幂等）
     * @param batchSize 单批最大归档行数
     * @param maxRounds 单次触发的最大批次数，兜底防死循环
     * @param dryRun 干跑：只统计不归档
     */
    public SuperviseReportArchiveTask(
            ExpiredSuperviseReportArchiver archiver,
            @Value("${yl.retention.supervise-archive.batch-size:500}") int batchSize,
            @Value("${yl.retention.supervise-archive.max-rounds:20}") int maxRounds,
            @Value("${yl.retention.supervise-archive.dry-run:false}") boolean dryRun) {
        this.archiver = archiver;
        this.batchSize = batchSize > 0 ? batchSize : 1;
        this.maxRounds = maxRounds > 0 ? maxRounds : 1;
        this.dryRun = dryRun;
    }

    /**
     * 归档已过保留期的监管上报记录（只读封存）。
     *
     * <p>本方法为普通业务方法（<b>无</b> {@code @Scheduled}），由 {@code RetentionChainScheduler} 与其余三个动作在同一次调度内
     * 串行调用。
     *
     * @return 实际归档行数（0 表示本轮无到期数据；异常时返回 0，等待下个周期重试）
     */
    public int archiveExpiredReports() {
        LocalDateTime cutoff = LocalDateTime.now();
        try {
            if (dryRun) {
                long pending = archiver.countExpired(cutoff);
                log.info(
                        "{} SUPERVISE-ARCHIVE dry-run：到期未归档 {} 条（cutoff={}），未执行归档",
                        LOG_PREFIX,
                        pending,
                        cutoff);
                return 0;
            }
            int total = 0;
            int rounds = 0;
            for (int round = 1; round <= maxRounds; round++) {
                int archived = archiver.archiveExpired(cutoff, batchSize);
                total += archived;
                rounds = round;
                if (archived < batchSize) {
                    break;
                }
            }
            if (total == 0) {
                log.info("{} SUPERVISE-ARCHIVE 无到期监管上报，本轮未归档（cutoff={}）", LOG_PREFIX, cutoff);
                return 0;
            }
            log.info(
                    "{} SUPERVISE-ARCHIVE 归档完成：{} 条，批次 {}，cutoff={}",
                    LOG_PREFIX,
                    total,
                    rounds,
                    cutoff);
            return total;
        } catch (RuntimeException ex) {
            log.error("{} SUPERVISE-ARCHIVE 归档失败，本轮放弃并等待下个周期重试（cutoff={}）", LOG_PREFIX, cutoff, ex);
            return 0;
        }
    }
}
