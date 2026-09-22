package cn.yl.modules.account.domain.thirdparty;

import java.time.LocalDateTime;

/**
 * 第三方账号绑定的「留存到期清理」端口（ER-14 · {@code retain_until}）。
 *
 * <p>只暴露两个动作，实现侧可以是 MyBatis 手写 SQL，也可以是别的存储 —— 任务类不感知。
 *
 * <p><b>幂等约定</b>：{@link #purgeExpired(LocalDateTime, int)} 的语义是「删除 {@code retain_until} 早于 cutoff
 * 的行」， 重复执行不产生额外效果（已删的行不再命中条件）。这一点决定了清理任务<b>不需要分布式锁</b>：多实例同时触发最坏只是空跑。
 */
public interface ExpiredBindingPurger {

    /**
     * 物理删除已过保留期的绑定行（限批）。
     *
     * @param cutoff 时点，删除 {@code retain_until < cutoff} 的行（{@code retain_until} 为 null 的行永不清理）
     * @param limit 本批最大删除行数，用于控制单事务规模
     * @return 实际删除行数
     */
    int purgeExpired(LocalDateTime cutoff, int limit);

    /**
     * 统计已过保留期但尚未清理的行数（用于干跑与清理后核对）。
     *
     * @param cutoff 时点
     * @return 待清理行数
     */
    long countExpired(LocalDateTime cutoff);
}
