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
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * 框架层拒绝处理器（CR-M2-007）：已认证但被 <b>Spring Security 授权层</b>拒绝 → {@code HTTP 403} + 统一响应体 {@code
 * R(10003)}。
 *
 * <p><b>用途定位（勿与业务越权混用）</b>：本项目所有业务端点经 {@code anyRequest().authenticated()} 后即进入方法层，框架层拒绝实际上仅在
 * {@code hasAuthority} 等表达式授权被使用时才触发（本项目未使用）。 故本类是"未来表达式授权"的<b>兜底能力位</b>，<b>不承担业务越权判定</b>。
 *
 * <p><b>职责边界</b>：业务层无权（{@code @RequiresPermission} 不满足）由 {@code PermissionAspect} 抛 {@code
 * BizException(ROLE_NOT_PERMITTED=20003)}，属 DispatcherServlet 内路径，由 {@code GlobalExceptionHandler}
 * 收敛为 {@code 200 + R.code}；与本类的 {@code 403 + R.code} 不重叠。
 */
@Component
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification = "ObjectMapper 是 Spring 容器管理的线程安全共享 Bean，构造器注入是标准 DI 用法"))
public class RestAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException accessDeniedException)
            throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        R<Void> body = R.fail(ErrorCode.FORBIDDEN.getCode(), ErrorCode.FORBIDDEN.getMessage());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
