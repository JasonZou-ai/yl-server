package cn.yl.modules.account.dto;

import lombok.Data;

/**
 * 所属机构<b>公开</b>联系方式视图（CR-M2-009 B-1-a）：{@code GET /api/v1/orgs/current/contact}。
 *
 * <p><b>为什么单独一个视图而不复用 {@link OrgView}</b>：{@code OrgView} 是机构<b>管理读面</b>（含 {@code orgCode} / {@code
 * orgType} / {@code regionCode} / {@code status} 等管理字段），本视图是<b>五角色通用的最小公开集</b>—— 老人 / 家属拿到 {@code
 * orgType}、{@code status} 这类内部字段没有业务意义，按最小够用原则裁剪（与 {@code ReportListItem} 只下发 6 字段同口径）。
 *
 * <p><b>不含 {@code contactPhoneEnc}</b>（机构电话密文列）：与 {@code OrgView} 同口径 —— 不返回密文，避免泄露可解密线索。
 * 机构电话若要明文展示，须另立「对公公开热线」明文列并走 DDL 授权（本轮未做，见 {@code
 * docs/reviews/2026-10-15-closeout-plan-for-approval.md} §八 B-1-a 分支）。
 */
@Data
public class OrgContactView {

    /** 机构 id（登录态所属机构，非请求参数）。 */
    private Long id;

    /** 机构名称。 */
    private String orgName;

    /** 联系人姓名（公开字段）。 */
    private String contactName;

    /** 机构地址。 */
    private String address;
}
