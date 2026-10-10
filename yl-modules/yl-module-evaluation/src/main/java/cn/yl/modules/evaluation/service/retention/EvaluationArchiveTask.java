package cn.yl.modules.evaluation.service.retention;

import cn.yl.modules.evaluation.domain.archive.ExpiredOrderArchiver;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 评估单超保留期归档任务（ER-03 {@code retain_until} · {@code ARCHIVED} 状态落地）。
 *
 * <p>已发布的评估单在保留期（{@code retain_until}）到期后转 {@code ARCHIVED}：<b>只读封存、不物理删除</b>。评估记录属 5
 * 年留存对象，到期只是脱离日常业务视图，不是销毁；真正的物理删除不在本任务职责内。
 *
 * <h3>调度选型与约束</h3>
 *
 * <p>沿用 ADR-0004 的三条硬约束（本任务<b>没有自己的 {@code @Scheduled}</b>，触发壳上移到链路编排，见下）：
 *
 * <ol>
 *   <li><b>不加分布式锁</b>：归档条件含 {@code status='PUBLISHED'}，归档后即离开命中集合，动作幂等；
 *   <li><b>限批执行</b>：{@code batch-size}（默认 500）+ {@code max-rounds}（默认 20），避免大表长事务；
 *   <li><b>失败可重试</b>：异常只记 error、不向调度器抛出，下一周期自然重试（幂等，重试安全）。
 * </ol>
 *
 * <h3>为什么触发壳在 yl-bootstrap</h3>
 *
 * <p>本任务与 ER-14 的三方绑定清理任务分属 evaluation / account 两个模块，二者为<b>平级兄弟模块</b>（互不依赖，只共同依赖 {@code
 * yl-api}），任一模块都无法编排对方。故按「一个定时清理链路、一次做两件事」的裁决，由唯一能同时看见两者的 {@code yl-bootstrap} 提供触发壳 {@code
 * RetentionChainScheduler}：业务动作留在本类的普通方法中，迁移到 XXL-Job 时只需替换 链路壳上的注解（ADR-0004「迁移边界」）。
 *
 * <p>配置（{@code yl.retention.eval-archive.*}）：{@code enabled} / {@code batch-size} / {@code
 * max-rounds} / {@code dry-run}。{@code dry-run=true} 时只统计不归档，用于上线前核对影响面。
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "yl.retention.eval-archive",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class EvaluationArchiveTask {

    private static final String LOG_PREFIX = "[retention]";

    private final ExpiredOrderArchiver archiver;

    private final int batchSize;

    private final int maxRounds;

    private final boolean dryRun;

    /**
     * @param archiver 归档端口（状态流转，幂等）
     * @param batchSize 单批最大归档行数
     * @param maxRounds 单次触发的最大批次数，兜底防死循环
     * @param dryRun 干跑：只统计不归档
     */
    public EvaluationArchiveTask(
            ExpiredOrderArchiver archiver,
            @Value("${yl.retention.eval-archive.batch-size:500}") int batchSize,
            @Value("${yl.retention.eval-archive.max-rounds:20}") int maxRounds,
            @Value("${yl.retention.eval-archive.dry-run:false}") boolean dryRun) {
        this.archiver = archiver;
        this.batchSize = batchSize > 0 ? batchSize : 1;
        this.maxRounds = maxRounds > 0 ? maxRounds : 1;
        this.dryRun = dryRun;
    }

    /**
     * 归档已过保留期的评估单。
     *
     * <p>本方法为普通业务方法（<b>无</b> {@code @Scheduled}），由 {@code RetentionChainScheduler} 与三方绑定清理在同一
     * 次调度内串行调用。
     *
     * @return 实际归档行数（0 表示本轮无到期数据；异常时返回 0，等待下个周期重试）
     */
    public int archiveExpiredOrders() {
        LocalDateTime cutoff = LocalDateTime.now();
        try {
            if (dryRun) {
                long pending = archiver.countExpired(cutoff);
                log.info(
                        "{} ARCHIVED dry-run：到期未归档 {} 单（cutoff={}），未执行归档",
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
                log.info("{} ARCHIVED 无到期评估单，本轮未归档（cutoff={}）", LOG_PREFIX, cutoff);
                return 0;
            }
            log.info("{} ARCHIVED 归档完成：{} 单，批次 {}，cutoff={}", LOG_PREFIX, total, rounds, cutoff);
            return total;
        } catch (RuntimeException ex) {
            log.error("{} ARCHIVED 归档失败，本轮放弃并等待下个周期重试（cutoff={}）", LOG_PREFIX, cutoff, ex);
            return 0;
        }
    }
}
