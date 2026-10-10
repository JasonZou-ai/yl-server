package cn.yl.modules.supervise.domain.retention;

import java.time.LocalDateTime;

/**
 * 监管上报记录「超保留期归档」端口（ER-03 {@code retain_until} · 归档并入 ADR-0004 调度链路，D-5）。
 *
 * <p>语义是「把 {@code retain_until} 早于 cutoff 的监管上报记录做<b>只读封存</b>（写入 {@code archived_at}）」，与 ER-14
 * 的三方绑定物理删除同属一个保留期清理链路，但<b>动作性质不同</b>：封存是<b>状态标记</b>，**行必须保留**——监管上报是对外凭证， 销毁不可逆；本端口的语义与兄弟模块的
 * {@code ExpiredOrderArchiver}（评估单转 {@code ARCHIVED}）同构。
 *
 * <p><b>幂等约定</b>：仅命中 {@code archived_at IS NULL}（未归档）的行，封存后即离开命中集合，重复执行不产生额外效果。因此任务
 * <b>不需要分布式锁</b>——多实例并发最坏只是重复空跑，与 ADR-0004「由选型派生的三条硬约束」一致。
 *
 * <p>任务类不感知存储实现：实现侧可以是手写 SQL，也可以是别的存储。
 */
public interface ExpiredSuperviseReportArchiver {

    /**
     * 归档已过保留期的监管上报记录（限批）。
     *
     * @param cutoff 时点，归档 {@code retain_until < cutoff} 的行（{@code retain_until} 为 null 的行永不归档：
     *     未写保留期即视为尚未进入留存周期）
     * @param limit 本批最大处理行数，用于控制单事务规模
     * @return 实际归档行数（**行不被删除**，仅写入 {@code archived_at}）
     */
    int archiveExpired(LocalDateTime cutoff, int limit);

    /**
     * 统计已过保留期但尚未归档的监管上报记录数（用于干跑与归档后核对）。
     *
     * @param cutoff 时点
     * @return 待归档行数
     */
    long countExpired(LocalDateTime cutoff);
}
