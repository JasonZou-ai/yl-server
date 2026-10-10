package cn.yl.modules.archive.domain.retention;

import java.time.LocalDateTime;

/**
 * 亲情绑定「授权到期流转」端口（D4-R1 / D-5 裁决 D5-D3 · {@code elder_family_bind}）。
 *
 * <p>语义是「把 {@link #expireExpired(LocalDateTime, int)} 命中的生效中绑定（{@code status = 2}，授权截止早于 cutoff）流转为
 * {@code status = 4}（已解绑/过期）」。这是<b>状态流转</b>，**不是删除**：行必须保留。
 *
 * <p><b>★ 绝不触碰 {@code deleted}</b>：{@code elder_family_bind} 的唯一键 {@code uk_elder_family} 含生成列
 * {@code active_uk = IF(deleted = 0, 1, NULL)}——**只有 {@code deleted = 1} 才让位唯一键**。而本表「重新申请复用活性行」的语义
 * 依赖活性行被 {@code UPDATE} 复位（D-4 §R-2）。若到期流转顺手改 {@code deleted}，会释放唯一键、破坏「复用」语义（同一老人↔家属
 * 会冒出多条活性行）。故本动作<b>只改 {@code status}</b>。
 *
 * <p><b>幂等约定</b>：仅命中 {@code status = 2}（生效中）的行，流转后即离开命中集合，重复执行不产生额外效果。因此任务<b>不需要
 * 分布式锁</b>——多实例并发最坏只是重复空跑，与 ADR-0004「由选型派生的三条硬约束」一致。
 *
 * <p><b>残留登记（D5-D5）</b>：{@code auth_end_at} 全仓<b>无写入口</b>（恒 NULL）⇒ 本动作在<b>生产为零命中</b>；待有效期来源卡
 * （{@code elder_authorization} / admin 落地后将 {@code auth_end_at} 写入）后自动生效。
 *
 * <p>任务类不感知存储实现：实现侧可以是手写 SQL，也可以是别的存储。
 */
public interface ExpiredFamilyBindingExpirer {

    /**
     * 流转已到期但仍在生效中的亲情绑定（限批）：{@code status 2 → 4}。
     *
     * @param cutoff 时点，流转 {@code auth_end_at < cutoff} 的绑定（{@code auth_end_at} 为 null
     *     的行永不流转：无有效期即视为长期有效）
     * @param limit 本批最大处理行数，用于控制单事务规模
     * @return 实际流转行数（**行不被删除，{@code deleted} 不变**，仅改 {@code status}）
     */
    int expireExpired(LocalDateTime cutoff, int limit);

    /**
     * 统计已到期但仍生效、尚未流转的亲情绑定数（用于干跑与流转后核对）。
     *
     * @param cutoff 时点
     * @return 待流转行数
     */
    long countExpired(LocalDateTime cutoff);
}
