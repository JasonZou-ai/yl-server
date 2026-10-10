package cn.yl.modules.report.domain.retention;

import java.time.LocalDateTime;

/**
 * 导出任务「到期标记」端口（P2-7，用户裁决 D1-B）。
 *
 * <p>语义是「把 {@code export_task} 中已过期（{@code expire_at IS NOT NULL AND expire_at <
 * cutoff}）但仍未标记（{@code status ≠ 4}）的行置为 {@code status =
 * 4}（已过期）」。这是<b>状态标记</b>，<b>不是删除</b>：行必须保留（本表无 {@code deleted} 列）。标记完成后，该行的键即退出 {@link
 * RetentionReferencePort#referencedExportKeys()} ⇒ 其文件可被回收。
 *
 * <p><b>为什么先改 DB 再移文件</b>：反向（先移文件再改行）会出现"行 {@code status ≠ 4} 仍宣称文件在用、文件却已不在"的<b>不一致</b>。
 * 而本顺序即使中途崩溃，行已 {@code 4}、文件仍在根内 ⇒ 下次扫描仍会回收（<b>最终收敛</b>，非缺陷，见设计 §10 P2-7-R4）。
 *
 * <p><b>幂等约定</b>：仅命中 {@code status ≠ 4} 的行，标记后即离开命中集合，重复执行不产生额外效果 ⇒ 动作<b>不需要分布式锁</b>，与
 * ADR-0004「由选型派生的三条硬约束」一致。
 */
public interface ExpiredExportTaskReaper {

    /**
     * 标记已过期的导出任务（限批）：{@code status → 4}。
     *
     * @param cutoff 时点，标记 {@code expire_at < cutoff} 的行（{@code expire_at} 为 NULL
     *     的行永不标记：未写有效期即视为长期有效）
     * @param limit 本批最大处理行数，用于控制单事务规模
     * @return 实际标记行数（<b>行不被删除</b>，仅改 {@code status}）
     */
    int markExpired(LocalDateTime cutoff, int limit);

    /**
     * 统计已过期但仍未标记（{@code status ≠ 4}）的导出任务数（用于干跑与标记后核对）。
     *
     * @param cutoff 时点
     * @return 待标记行数
     */
    long countExpired(LocalDateTime cutoff);
}
