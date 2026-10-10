package cn.yl.modules.archive.service.retention;

import cn.yl.modules.archive.domain.retention.ExpiredFamilyBindingExpirer;
import java.time.LocalDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 亲情绑定授权到期流转任务（D4-R1 · {@code status 2 → 4} 落地，D-5 裁决 D5-D3）。
 *
 * <p>生效中（{@code status = 2}）的亲情绑定在授权截止（{@code auth_end_at}）到期后流转为 {@code status = 4}（已解绑/过期）。
 * 这是<b>展示态一致</b>动作，不是合规缺口：第三闸 {@code MY_ELDER_SUBQUERY} 子查询已含 {@code (auth_end_at IS NULL OR >
 * NOW())}，到期即<b>数据不可达</b>（fail-closed），安全性不依赖本流转。
 *
 * <p><b>★ 绝不触碰 {@code deleted}</b>：唯一键 {@code active_uk = IF(deleted = 0, 1, NULL)} 只有 {@code
 * deleted = 1} 才 让位；改 {@code deleted} 会释放唯一键、破坏「重新申请复用活性行」语义（D-4 §R-2）。本任务只改 {@code status}。
 *
 * <h3>调度选型与约束</h3>
 *
 * <p>沿用 ADR-0004 的三条硬约束（本任务<b>没有自己的 {@code @Scheduled}</b>，触发壳上移到链路编排）：
 *
 * <ol>
 *   <li><b>不加分布式锁</b>：流转条件含 {@code status = 2}，流转后即离开命中集合，动作幂等；
 *   <li><b>限批执行</b>：{@code batch-size}（默认 500）+ {@code max-rounds}（默认 20），避免大表长事务；
 *   <li><b>失败可重试</b>：异常只记 error、不向调度器抛出，下一周期自然重试（幂等，重试安全）。
 * </ol>
 *
 * <h3>为什么触发壳在 yl-bootstrap</h3>
 *
 * <p>本任务与其余保留期动作分属 archive / account / evaluation / supervise 四个<b>平级兄弟模块</b>（互不依赖，只共同依赖 {@code
 * yl-api}），任一模块都无法编排对方。故由唯一能同时看见各模块的 {@code yl-bootstrap} 提供触发壳 {@code
 * RetentionChainScheduler}：业务动作留在本类的普通方法中，迁移到 XXL-Job 时只需替换链路壳上的注解（ADR-0004「迁移边界」）。
 *
 * <p>配置（{@code yl.retention.family-bind-expire.*}）：{@code enabled} / {@code batch-size} / {@code
 * max-rounds} / {@code dry-run}。{@code dry-run=true} 时只统计不流转，用于上线前核对影响面。
 *
 * <p><b>残留登记（D5-D5）</b>：{@code auth_end_at} 全仓<b>无写入口</b>（恒 NULL）⇒ 本任务在<b>生产为零命中</b>（无异常、无日志、返回
 * 0）。待有效期来源卡（{@code elder_authorization} / admin 写 {@code auth_end_at}）落地后自动生效。
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "yl.retention.family-bind-expire",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class FamilyBindingExpireTask {

    private static final String LOG_PREFIX = "[retention]";

    private final ExpiredFamilyBindingExpirer expirer;

    private final int batchSize;

    private final int maxRounds;

    private final boolean dryRun;

    /**
     * @param expirer 到期流转端口（状态流转，幂等）
     * @param batchSize 单批最大流转行数
     * @param maxRounds 单次触发的最大批次数，兜底防死循环
     * @param dryRun 干跑：只统计不流转
     */
    public FamilyBindingExpireTask(
            ExpiredFamilyBindingExpirer expirer,
            @Value("${yl.retention.family-bind-expire.batch-size:500}") int batchSize,
            @Value("${yl.retention.family-bind-expire.max-rounds:20}") int maxRounds,
            @Value("${yl.retention.family-bind-expire.dry-run:false}") boolean dryRun) {
        this.expirer = expirer;
        this.batchSize = batchSize > 0 ? batchSize : 1;
        this.maxRounds = maxRounds > 0 ? maxRounds : 1;
        this.dryRun = dryRun;
    }

    /**
     * 流转已到期的生效中亲情绑定。
     *
     * <p>本方法为普通业务方法（<b>无</b> {@code @Scheduled}），由 {@code RetentionChainScheduler} 与其余三个动作在同一次调度内
     * 串行调用。
     *
     * @return 实际流转行数（0 表示本轮无到期数据；异常时返回 0，等待下个周期重试）
     */
    public int expireExpiredBindings() {
        LocalDateTime cutoff = LocalDateTime.now();
        try {
            if (dryRun) {
                long pending = expirer.countExpired(cutoff);
                log.info(
                        "{} FAMILY-BIND-EXPIRE dry-run：到期未流转 {} 条（cutoff={}），未执行流转",
                        LOG_PREFIX,
                        pending,
                        cutoff);
                return 0;
            }
            int total = 0;
            int rounds = 0;
            for (int round = 1; round <= maxRounds; round++) {
                int expired = expirer.expireExpired(cutoff, batchSize);
                total += expired;
                rounds = round;
                if (expired < batchSize) {
                    break;
                }
            }
            if (total == 0) {
                log.info("{} FAMILY-BIND-EXPIRE 无到期亲情绑定，本轮未流转（cutoff={}）", LOG_PREFIX, cutoff);
                return 0;
            }
            log.info(
                    "{} FAMILY-BIND-EXPIRE 流转完成：{} 条，批次 {}，cutoff={}",
                    LOG_PREFIX,
                    total,
                    rounds,
                    cutoff);
            return total;
        } catch (RuntimeException ex) {
            log.error(
                    "{} FAMILY-BIND-EXPIRE 流转失败，本轮放弃并等待下个周期重试（cutoff={}）", LOG_PREFIX, cutoff, ex);
            return 0;
        }
    }
}
