package cn.yl.modules.account.domain.thirdparty;

/**
 * 第三方平台身份（平台换证结果，B2 / rpW1xZ）。
 *
 * <p>只承载「解析本服务账号所必需」的最小字段。平台侧用户标识（{@code openId}）**不落库、不入日志** ——对齐 ER-11 埋点字典 S2
 * 禁采口径（平台标识属可关联到具体自然人的外部标识），仅在本次换证与绑定解析的内存中流转。
 *
 * @param platform 平台码（WECHAT / DOUYIN）
 * @param openId 平台侧用户标识（不持久化）
 * @param phone 平台侧手机号（可为 null；缺失时无法完成手机号绑定解析）
 */
public record ThirdPartyIdentity(String platform, String openId, String phone) {}
