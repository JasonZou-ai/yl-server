package cn.yl.api.security;

import cn.yl.common.api.R;
import cn.yl.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * 未认证入口点（CR-M2-007）：未携带凭据 → {@code HTTP 401} + 统一响应体 {@code R(10002)}。
 *
 * <p>Spring Security 默认的 {@code Http403ForbiddenEntryPoint} 对「未携带凭据」返回 403 且响应体非 {@code
 * R}，与本仓「未认证统一 401 + 统一响应体」的承诺矛盾（客户端会把"未登录"误判为"已登录但无权限"）。 本类把该承诺变为事实：状态码 {@code 401}，响应体经全局同一
 * {@link ObjectMapper} 序列化为 {@link R}，{@code code = } {@link ErrorCode#UNAUTHORIZED}（10002）。
 *
 * <p><b>职责边界</b>：本类只处理<b>过滤器链内</b>的 {@link AuthenticationException}（第一闸）； {@code PermissionAspect}
 * 抛出的 {@code BizException}（业务层无权/缺二次验证）发生在 DispatcherServlet 内， 仍由 {@code GlobalExceptionHandler}
 * 收敛为 {@code 200 + R.code}，两条路径不重叠。
 */
@Component
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification = "ObjectMapper 是 Spring 容器管理的线程安全共享 Bean，构造器注入是标准 DI 用法"))
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        R<Void> body =
                R.fail(ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMessage());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
