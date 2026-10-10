package cn.yl.modules.evaluation.dto;

import lombok.Data;

/**
 * 复核动作请求（PRD §4.4 状态机）。
 *
 * <p>驳回（REJECT）与作废（VOID）时 {@code opinion} 必填：退回录入人须保留意见，否则无法据以修改重提；发布（PUBLISH）时可选。
 *
 * <p><b>CR-M2-002：本 DTO 已移除 {@code operatorId}</b>。此前操作者由请求体提供，导致任何持合法令牌的账号只要知道 {@code orderId}
 * 就能以他人名义发布/驳回/作废他人评估单（实测：评估员令牌传机构管理员 ID 即成功）。现操作者<b>一律由服务端从登录态取</b>（{@code
 * SecurityUtils.requireUserId()}），授权判定交给第二闸权限码与第四闸二次验证。
 *
 * <p><b>契约兼容性说明</b>：旧客户端（含安卓端 {@code AssessmentChainApi.publish/reject}）若仍在请求体携带 {@code
 * operatorId}，服务端会因为 Jackson「忽略未知字段」而静默丢弃该字段，<b>不会返回 400</b>；因此四端无需同批次发版即可安全上线本变更。
 * 但该字段已无任何语义，四端应在下次发版时清理。
 */
@Data
public class ReviewActionRequest {

    /** 复核/作废意见（驳回与作废必填，发布可选）。 */
    private String opinion;
}
