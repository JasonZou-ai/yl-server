package cn.yl.modules.account.domain.retention;

import java.time.LocalDateTime;

/**
 * 第三方账号绑定的「留存到期清理」端口（ER-14 · {@code retain_until}）。
 *
 * <p>只暴露两个动作，实现侧可以是 MyBatis 手写 SQL，也可以是别的存储 —— 任务类不感知。
 *
 * <p><b>形态归一（D-5 裁决 D5-D4）</b>：本端口原位于 {@code domain/thirdparty/}（与登录适配器混放），已迁至 {@code
 * domain/retention/}，与兄弟模块的保留期端口（{@code domain/archive/ExpiredOrderArchiver}、{@code
 * domain/retention/ExpiredSuperviseReportArchiver}、{@code
 * domain/retention/ExpiredFamilyBindingExpirer}） 同址。**纯移动 + 改 import，零行为变更。**
 *
 * <p><b>幂等约定</b>：{@link #purgeExpired(LocalDateTime, int)} 的语义是「删除 {@code retain_until} 早于 cutoff
 * 的行」， 重复执行不产生额外效果（已删的行不再命中条件）。这一点决定了清理任务<b>不需要分布式锁</b>：多实例同时触发最坏只是空跑。
 *
 * <p><b>动作性质</b>：本端口是<b>物理删除</b>（注销后保留 30 天，超期不得继续留存），与归档端口的「只读封存、不销毁」不同。
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
