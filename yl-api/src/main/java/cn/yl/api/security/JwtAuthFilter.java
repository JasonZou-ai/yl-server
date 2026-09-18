package cn.yl.api.security;

import cn.yl.common.api.R;
import cn.yl.common.exception.ErrorCode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 统一鉴权过滤器（B1-2 / 子任务 rPX9p2）。
 *
 * <p>行为：解析 {@code Authorization: Bearer <token>} → 验签 → 构造 {@link LoginUser} 主体并写入
 * SecurityContext。令牌非法/过期一律返回 401 与统一响应体，不泄露具体失败原因。
 *
 * <p>说明：只接受 {@code typ=access} 的令牌；refresh 令牌仅可用于 {@code /auth/refresh}。
 */
@Slf4j
@Component
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification =
                                "JwtTokenProvider 与 ObjectMapper 均为 Spring 容器管理的线程安全共享 Bean，"
                                        + "构造器注入是标准 DI 用法"))
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenProvider tokenProvider;
    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        try {
            Claims claims = tokenProvider.parse(token);
            String type = claims.get(JwtTokenProvider.CLAIM_TYPE, String.class);
            if (!JwtTokenProvider.TYPE_ACCESS.equals(type)) {
                // 非访问令牌不得用于业务接口
                chain.doFilter(request, response);
                return;
            }
            long userId = claims.get(JwtTokenProvider.CLAIM_USER_ID, Number.class).longValue();
            Number orgIdRaw = claims.get(JwtTokenProvider.CLAIM_ORG_ID, Number.class);
            Long orgId = orgIdRaw == null ? null : orgIdRaw.longValue();
            @SuppressWarnings("unchecked")
            List<String> roles =
                    (List<String>)
                            claims.getOrDefault(
                                    JwtTokenProvider.CLAIM_ROLES, Collections.emptyList());

            LoginUser loginUser = new LoginUser(userId, orgId, roles);
            List<SimpleGrantedAuthority> authorities =
                    roles.stream().map(r -> new SimpleGrantedAuthority("ROLE_" + r)).toList();
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(loginUser, null, authorities);
            SecurityContextHolder.getContext().setAuthentication(auth);
            chain.doFilter(request, response);
        } catch (JwtException | IllegalArgumentException e) {
            // 不返回具体原因（防探测），仅记录服务端日志
            log.warn(
                    "令牌解析失败 uri={} reason={}",
                    request.getRequestURI(),
                    e.getClass().getSimpleName());
            writeUnauthorized(response);
        }
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        R<Void> body =
                R.fail(ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMessage());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
