package cn.yl.modules.account.controller;

import cn.yl.api.security.LoginUser;
import cn.yl.common.api.R;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.account.domain.LoginChannel;
import cn.yl.modules.account.dto.LoginRequest;
import cn.yl.modules.account.dto.LoginResponse;
import cn.yl.modules.account.dto.RefreshRequest;
import cn.yl.modules.account.dto.SecondVerifyRequest;
import cn.yl.modules.account.dto.SecondVerifyResponse;
import cn.yl.modules.account.security.SecondVerifyGuard;
import cn.yl.modules.account.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接口（B2 / rpW1xZ，设计 §6）。四端统一入口，统一换发本服务双令牌。
 *
 * <p>{@code /login/**} 与 {@code /refresh} 放行（见 SecurityConfig）；{@code /second-verify} 需已登录。
 */
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /** 多端登录：{channel} ∈ wechat/douyin/ios/android（大小写不敏感）。 */
    @PostMapping("/login/{channel}")
    public R<LoginResponse> login(
            @PathVariable String channel,
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest) {
        LoginChannel loginChannel = LoginChannel.of(channel);
        return R.ok(
                authService.login(
                        loginChannel,
                        request,
                        clientIp(httpRequest),
                        httpRequest.getHeader("User-Agent")));
    }

    /** 刷新 access 令牌。 */
    @PostMapping("/refresh")
    public R<LoginResponse> refresh(@Valid @RequestBody RefreshRequest request) {
        return R.ok(authService.refresh(request.getRefreshToken()));
    }

    /** 获取二次验证一次性凭据（需已登录）。 */
    @PostMapping("/second-verify")
    public R<SecondVerifyResponse> secondVerify(@Valid @RequestBody SecondVerifyRequest request) {
        long userId = currentUserId();
        String token =
                authService.issueSecondVerifyToken(
                        userId, request.getMethod(), request.getCredential());
        return R.ok(new SecondVerifyResponse(token, SecondVerifyGuard.TTL_SECONDS));
    }

    private long currentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser user) {
            return user.userId();
        }
        throw new BizException(ErrorCode.UNAUTHORIZED);
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
