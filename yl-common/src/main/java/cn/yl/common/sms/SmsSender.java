package cn.yl.common.sms;

/**
 * 短信下发通道端口（P2-8-A，设计 §4.5）。
 *
 * <p>应用层只依赖本端口；A 段实现为 dev 日志桩（{@code LoggingSmsSender}）与 fail-closed 桩 （{@code
 * UnavailableSmsSender}），真实服务商实现（腾讯 / 阿里）属 P2-8-B。
 */
public interface SmsSender {

    /**
     * 下发短信验证码。
     *
     * <p>★ 实现<b>不得</b>将 {@code phone} / {@code code} 写入日志（dev 桩受 {@code yl.sms.dev-log-code}
     * 开关约束，且手机号一律掩码）。
     *
     * @param phone 手机号明文（仅在内存中使用，禁止落库/落日志）
     * @param purpose 验证码用途
     * @param code 验证码明文（禁止落日志）
     * @throws cn.yl.common.exception.BizException {@code SMS_SEND_FAILED} ——
     *     通道不可用时<b>必须显式失败</b>，绝不静默假装成功
     */
    void send(String phone, SmsPurpose purpose, String code);
}
