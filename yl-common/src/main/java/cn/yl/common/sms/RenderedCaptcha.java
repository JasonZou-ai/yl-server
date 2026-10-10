package cn.yl.common.sms;

/**
 * 渲染结果（P2-8-A）。
 *
 * @param code 验证码明文（禁止落日志）
 * @param dataUri PNG data URI（{@code data:image/png;base64,…}），可直接作为 SMS-01 响应的 {@code
 *     captchaImage}
 */
public record RenderedCaptcha(String code, String dataUri) {}
