package cn.yl.modules.evaluation.domain.archive;

import java.time.LocalDateTime;

/**
 * 评估单「超保留期归档」端口（ER-03 {@code retain_until} · 归档并入 ADR-0004 调度链路）。
 *
 * <p>语义是「把 {@code retain_until} 早于 cutoff 的<b>已发布</b>评估单转为 {@code ARCHIVED}（只读封存）」，与 ER-14
 * 的三方绑定物理删除同属一个保留期清理链路，但<b>动作性质不同</b>：归档是<b>状态流转</b>，数据必须保留（评估记录属 5 年留存 对象，不得物理删除）。
 *
 * <p><b>幂等约定</b>：仅命中 {@code status='PUBLISHED'}，归档后状态即离开命中集合，重复执行不产生额外效果。因此任务
 * <b>不需要分布式锁</b>——多实例并发最坏只是重复空跑，与 ADR-0004「由选型派生的三条硬约束」一致。
 *
 * <p>任务类不感知存储实现：实现侧可以是手写 SQL，也可以是别的存储。
 */
public interface ExpiredOrderArchiver {

    /**
     * 归档已过保留期的评估单（限批）。
     *
     * @param cutoff 时点，归档 {@code retain_until < cutoff} 的单（{@code retain_until} 为 null 的单永不归档：
     *     未写保留期即视为尚未进入留存周期，见 {@code publish} 的写入口径）
     * @param limit 本批最大处理行数，用于控制单事务规模
     * @return 实际归档行数
     */
    int archiveExpired(LocalDateTime cutoff, int limit);

    /**
     * 统计已过保留期但尚未归档的评估单数（用于干跑与归档后核对）。
     *
     * @param cutoff 时点
     * @return 待归档行数
     */
    long countExpired(LocalDateTime cutoff);
}
