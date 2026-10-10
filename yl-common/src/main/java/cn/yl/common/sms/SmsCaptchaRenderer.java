package cn.yl.common.sms;

/**
 * 图形验证码渲染端口（P2-8-A）。
 *
 * <p>业务模块（{@code yl-module-account} 的 {@code SmsCodeService}）依赖本端口；实现（{@code
 * AwtCaptchaRenderer}，JDK AWT）位于 {@code yl-infrastructure}。跨层依赖方向：业务 → 端口（yl-common）， 实现 →
 * 端口，业务不感知实现。
 */
public interface SmsCaptchaRenderer {

    /**
     * 渲染一张图形验证码。
     *
     * @return 验证码明文与其 PNG data URI
     */
    RenderedCaptcha render();
}
