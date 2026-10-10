package cn.yl.common.sms;

/**
 * 短信核身 ticket 载荷（P2-8-A，设计 §4.4）。
 *
 * <p>Redis 值格式：{@code {phoneHash}:{scene}:{purpose}}。SMS-04 兑换登录态时据此按 {@code phone_hash} 查用户，并以
 * {@code scene} 映射 {@code login_log.device_type}。
 *
 * @param phoneHash 手机号摘要
 * @param scene 端侧场景（原样字符串，映射时宽松回落 WEB）
 * @param purpose 验证码用途
 */
public record SmsTicket(String phoneHash, String scene, SmsPurpose purpose) {}
