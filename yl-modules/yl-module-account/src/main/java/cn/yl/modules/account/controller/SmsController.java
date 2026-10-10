package cn.yl.modules.account.controller;

import cn.yl.common.api.R;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.account.dto.SmsCaptchaRequest;
import cn.yl.modules.account.dto.SmsCaptchaResponse;
import cn.yl.modules.account.dto.SmsSendRequest;
import cn.yl.modules.account.dto.SmsSendResponse;
import cn.yl.modules.account.dto.SmsTicketResponse;
import cn.yl.modules.account.dto.SmsVerifyRequest;
import cn.yl.modules.account.service.sms.SmsCodeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 短信核身通道控制器（P2-8-A，设计 §3.1/§5）：SMS-01 图形验证码 / SMS-02 发送 / SMS-03 校验。
 *
 * <p><b>未登录前置</b>端点：三个操作均 {@code x-required-permission: none} + {@code x-data-scope.mode:
 * public}，安全边界是通道自身（频控 + 图形验证 + 一次性核销），须进 {@code SecurityConfig} 白名单与 {@code
 * ControllerAuthorizationCoverageTest} 白名单。
 *
 * <p>业务失败一律 <b>HTTP 200 + {@code R.code}</b>（参数校验在 Service 层手动完成，故不用 Bean Validation 以免触发 400 语义）。
 */
@RestController
@RequestMapping("/api/v1/sms")
@RequiredArgsConstructor
public class SmsController {

    private final SmsCodeService smsCodeService;

    /** SMS-01：获取图形验证码（body 可缺省；scene 仅作文档/日志）。 */
    @PostMapping("/captcha-image")
    public R<SmsCaptchaResponse> captchaImage(
            @RequestBody(required = false) SmsCaptchaRequest request) {
        String scene = request == null ? null : request.getScene();
        return R.ok(smsCodeService.generateCaptcha(scene));
    }

    /** SMS-02：发送短信验证码。 */
    @PostMapping("/send")
    public R<SmsSendResponse> send(
            @RequestBody(required = false) SmsSendRequest request, HttpServletRequest httpRequest) {
        if (request == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少请求体");
        }
        return R.ok(
                smsCodeService.send(
                        request, clientIp(httpRequest), httpRequest.getHeader("User-Agent")));
    }

    /** SMS-03：校验短信验证码（一次性核销）。 */
    @PostMapping("/verify")
    public R<SmsTicketResponse> verify(@RequestBody(required = false) SmsVerifyRequest request) {
        if (request == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少请求体");
        }
        return R.ok(smsCodeService.verify(request));
    }

    /** 取客户端 IP（优先 {@code X-Forwarded-For} 首段）。 */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
