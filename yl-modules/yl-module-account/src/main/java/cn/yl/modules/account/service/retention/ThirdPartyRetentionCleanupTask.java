package cn.yl.modules.account.service.retention;

import cn.yl.modules.account.domain.thirdparty.ExpiredBindingPurger;
import cn.yl.modules.account.service.AuditService;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 第三方账号绑定的留存到期清理任务（ER-14 · {@code retain_until}）。
 *
 * <p>账号注销后绑定行保留 30 天（PM 裁决 2026-09-21），到期由本任务<b>物理删除</b>，避免超期留存构成合规缺口。
 *
 * <h3>调度选型与约束</h3>
 *
 * <p>采 {@code @Scheduled} 而非 Quartz / XXL-Job（理由见 {@link
 * cn.yl.modules.account.config.SchedulingConfig}）。随之而来的 三点约束，实现上已分别处理：
 *
 * <ol>
 *   <li><b>多实例重复执行</b>：不加分布式锁。删除条件是 {@code retain_until < now} 且<b>幂等</b>，并发执行最坏是重复空跑，
 *       不会误删、不会数据错乱；加锁反而引入锁失效与死锁风险。留痕以「实际删除行数 {@code > 0}」为门槛，因此<b>只有真正删到
 *       数据的那一个实例会写审计</b>，不会每个实例都写一条。
 *   <li><b>单批规模</b>：限批 {@code batch-size}（默认 500）+ 最大轮次 {@code max-rounds}（默认 20），避免大表长事务与 从库延迟。
 *   <li><b>失败可观测</b>：异常只记 {@code error} 日志、不向调度器抛出，避免刷屏堆栈；下一周期自然重试（幂等，重试安全）。
 * </ol>
 *
 * <p>配置（{@code yl.retention.third-party.*}）：{@code enabled} / {@code cron} / {@code batch-size} /
 * {@code max-rounds} / {@code dry-run}。{@code dry-run=true} 时只统计不删除，用于上线前核对影响面。
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "yl.retention.third-party",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ThirdPartyRetentionCleanupTask {

    /** 审计动作名：与 ER-11 字典的命名风格一致（大写下划线）。 */
    private static final String AUDIT_ACTION = "THIRD_PARTY_PURGE";

    private static final String AUDIT_BIZ_TYPE = "third_party";

    private static final String LOG_PREFIX = "[retention]";

    private final ExpiredBindingPurger purger;

    private final AuditService auditService;

    private final int batchSize;

    private final int maxRounds;

    private final boolean dryRun;

    /**
     * @param purger 清理端口（物理删除，幂等）
     * @param auditService 审计留痕服务
     * @param batchSize 单批最大删除行数
     * @param maxRounds 单次触发的最大批次数，兜底防死循环
     * @param dryRun 干跑：只统计不删除
     */
    public ThirdPartyRetentionCleanupTask(
            ExpiredBindingPurger purger,
            AuditService auditService,
            @Value("${yl.retention.third-party.batch-size:500}") int batchSize,
            @Value("${yl.retention.third-party.max-rounds:20}") int maxRounds,
            @Value("${yl.retention.third-party.dry-run:false}") boolean dryRun) {
        this.purger = purger;
        this.auditService = auditService;
        this.batchSize = batchSize > 0 ? batchSize : 1;
        this.maxRounds = maxRounds > 0 ? maxRounds : 1;
        this.dryRun = dryRun;
    }

    /**
     * 清理已过保留期的三方绑定行。默认每天凌晨 03:15 触发。
     *
     * <p>本方法即「触发壳」；迁移到 XXL-Job 时只需把注解替换为 {@code @XxlJob}，方法体不动。
     */
    @Scheduled(cron = "${yl.retention.third-party.cron:0 15 3 * * *}")
    public void purgeExpiredBindings() {
        LocalDateTime cutoff = LocalDateTime.now();
        try {
            if (dryRun) {
                log.info(
                        "{} dry-run：到期未清理 {} 条（cutoff={}），未执行删除",
                        LOG_PREFIX,
                        purger.countExpired(cutoff),
                        cutoff);
                return;
            }
            int total = 0;
            int rounds = 0;
            for (int round = 1; round <= maxRounds; round++) {
                int purged = purger.purgeExpired(cutoff, batchSize);
                total += purged;
                rounds = round;
                if (purged < batchSize) {
                    break;
                }
            }
            if (total == 0) {
                log.info("{} 无到期数据，本轮未删除（cutoff={}）", LOG_PREFIX, cutoff);
                return;
            }
            recordAudit(total, cutoff);
            log.info("{} 清理完成：删除 {} 条，批次 {}，cutoff={}", LOG_PREFIX, total, rounds, cutoff);
        } catch (RuntimeException ex) {
            log.error("{} 清理失败，本轮放弃并等待下个周期重试（cutoff={}）", LOG_PREFIX, cutoff, ex);
        }
    }

    /** 写审计留痕；留痕失败不影响已完成的清理，只记 error。 */
    private void recordAudit(int total, LocalDateTime cutoff) {
        String detail =
                String.format("{\"purged\":%d,\"cutoff\":\"%s\",\"dryRun\":false}", total, cutoff);
        try {
            auditService.record(AUDIT_ACTION, AUDIT_BIZ_TYPE, false, detail);
        } catch (RuntimeException ex) {
            log.error("{} 清理已完成但审计留痕失败：purged={}", LOG_PREFIX, total, ex);
        }
    }
}
