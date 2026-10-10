package cn.yl.modules.archive.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * elder↔user 绑定入参（D-4，{@code POST /api/v1/elders/{elderId}/user-binding}）。
 *
 * <p>{@code elderId} 在 path；请求体<b>只含</b> {@code userId}，不含任何身份字段（CR-M2-002 铁律）， <b>也不含</b> {@code
 * authEndAt}（D-4a §1.8 裁决：{@code elder} 表无 {@code auth_end_at} 列且本卡零 DDL ⇒
 * <b>收窄契约</b>：不接收、不落库、不告警。<b>接受但不落库 = 静默丢弃安全语义字段</b>，违「宁可失败，不可假数据」； 「有效期」的合法承载是 {@code
 * elder_authorization}（ER-07），本卡明令不接，故此处无合法承载）。
 *
 * <p><b>★ 承重语义：老人↔账号为 1:1</b>（{@code elder.uk_elder_user} 唯一）。绑定冲突一律返回 {@code 10005}，<b>绝不 {@code
 * 500}</b>。
 */
@Data
public class UserBindingRequest {

    /** 目标 ELDER 账号 {@code sys_user.id}。 */
    @NotNull(message = "userId 不能为空")
    private Long userId;
}
