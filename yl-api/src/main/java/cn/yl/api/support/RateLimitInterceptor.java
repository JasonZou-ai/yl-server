package cn.yl.api.support;

import cn.yl.api.security.LoginUser;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.common.ratelimit.RateLimiter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 统一限流拦截器（B1-2 / 子任务 rPX9p2）。
 *
 * <p>策略（默认值，可按接口调优）：
 *
 * <ul>
 *   <li>已登录用户：按 {@code userId + 接口} 维度，60 秒 120 次
 *   <li>未登录/匿名：按 {@code ip + 接口} 维度，60 秒 30 次（如登录、短信验证码类接口更严）
 * </ul>
 *
 * <p>命中限流返回业务码 {@link ErrorCode#RATE_LIMITED}（10429），四端统一提示文案。
 */
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private static final int USER_LIMIT = 120;
    private static final int ANON_LIMIT = 30;
    private static final int WINDOW_SECONDS = 60;

    private final RateLimiter rateLimiter;

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        String api = request.getMethod() + " " + request.getRequestURI();
        String dimension;
        int limit;

        LoginUser loginUser = currentLoginUser();
        if (loginUser != null) {
            dimension = "user:" + loginUser.userId() + ":" + api;
            limit = USER_LIMIT;
        } else {
            dimension = "ip:" + clientIp(request) + ":" + api;
            limit = ANON_LIMIT;
        }

        if (!rateLimiter.tryAcquire(dimension, limit, WINDOW_SECONDS)) {
            throw new BizException(ErrorCode.RATE_LIMITED);
        }
        return true;
    }

    private LoginUser currentLoginUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof LoginUser user) {
            return user;
        }
        return null;
    }

    /** 取真实客户端 IP，优先反向代理透传头（部署于网关/负载均衡后时必需）。 */
    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String realIp = request.getHeader("X-Real-IP");
        if (realIp != null && !realIp.isBlank()) {
            return realIp.trim();
        }
        return request.getRemoteAddr();
    }
}
