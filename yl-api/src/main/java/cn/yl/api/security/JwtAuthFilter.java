package cn.yl.api.security;

import cn.yl.common.api.R;
import cn.yl.common.exception.ErrorCode;
import cn.yl.common.security.TokenBlacklistStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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
 *
 * <p><b>会话入口端点不解析令牌</b>（{@code /auth/login/**} 与 {@code /auth/refresh}）：这两个端点是"建立新会话"的入口，
 * 必须**不继承既有会话身份**。否则会出现两类问题：
 *
 * <ul>
 *   <li>令牌过期后无法重新登录——旧 token 一律 401，用户被锁死；
 *   <li>安全上下文被旧身份占用，数据域过滤器（{@code DataScopePermissionHandler}）会给登录自身的 {@code sys_user} 查询追加 {@code
 *       sys_user.id = 旧用户} 条件，导致**已登录状态下无法登录其他账号**（返回"账号不存在"）。 该缺陷由真机端到端联调暴露：端上「切换复核人身份发布」必然失败。
 * </ul>
 *
 * <p><b>登录态为完整装配（CR-M2-002 缺口 B）</b>：access 令牌携带 {@code perms(权限码) / orgIds(归属机构) / ds(数据域)}
 * 三项载荷，本过滤器把它们一并装配进 {@link LoginUser}。此前只用 3 参兼容构造器（perms 空、dataScope=1），会导致权限码校验"全量 403
 * 假性收紧"与机构数据域失效——两者互为掩护，必须与端点鉴权同批次修复。
 *
 * <p><b>登出黑名单（D-4）</b>：{@code parse} 成功后、装配登录态之前查询 {@link TokenBlacklistStore}，命中即按未认证短路（{@code
 * writeUnauthorized} → 401 + {@code R.code=10002}），<b>不装配任何身份</b>。使 {@code POST /auth/logout} 后的旧
 * access 令牌立即失效。
 */
@Slf4j
@Component
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification =
                                "JwtTokenProvider / ObjectMapper / TokenBlacklistStore 均为 Spring 容器管理的线程安全共享 Bean，"
                                        + "构造器注入是标准 DI 用法"))
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    /** 角色权限前缀（Spring Security 约定）。 */
    private static final String ROLE_PREFIX = "ROLE_";

    /** 令牌未携带数据域声明时的兜底口径（1=本人，最严格，防"载荷缺失即放行"）。 */
    private static final int FALLBACK_DATA_SCOPE = 1;

    /** 会话入口端点前缀（不继承既有登录态；`/auth/second-verify` 需已登录，故不在此列）。 */
    private static final List<String> SESSION_ENTRY_PATHS =
            List.of("/api/v1/auth/login", "/api/v1/auth/refresh");

    private final JwtTokenProvider tokenProvider;
    private final ObjectMapper objectMapper;
    private final TokenBlacklistStore blacklistStore;

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isSessionEntry(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            chain.doFilter(request, response);
            return;
        }
        String token = header.substring(BEARER_PREFIX.length()).trim();
        try {
            Claims claims = tokenProvider.parse(token);
            if (blacklistStore.isBlacklisted(token)) {
                // 登出后旧 access 令牌：命中黑名单即早短路为未认证（不装配身份），401 + R.code=10002
                log.info("令牌命中黑名单，按未认证处理 uri={}", request.getRequestURI());
                writeUnauthorized(response);
                return;
            }
            String type = claims.get(JwtTokenProvider.CLAIM_TYPE, String.class);
            if (!JwtTokenProvider.TYPE_ACCESS.equals(type)) {
                // 非访问令牌不得用于业务接口
                chain.doFilter(request, response);
                return;
            }
            LoginUser loginUser = assemble(claims);
            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(
                            loginUser, null, authorities(loginUser));
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

    /** 从令牌声明完整装配登录态（身份 + 角色 + 权限 + 机构域 + 数据域）。 */
    private LoginUser assemble(Claims claims) {
        long userId = claims.get(JwtTokenProvider.CLAIM_USER_ID, Number.class).longValue();
        Number orgIdRaw = claims.get(JwtTokenProvider.CLAIM_ORG_ID, Number.class);
        Long orgId = orgIdRaw == null ? null : orgIdRaw.longValue();
        return new LoginUser(
                userId,
                orgId,
                stringList(claims, JwtTokenProvider.CLAIM_ROLES),
                stringList(claims, JwtTokenProvider.CLAIM_PERMS),
                longList(claims, JwtTokenProvider.CLAIM_ORG_IDS),
                intClaim(claims, JwtTokenProvider.CLAIM_DATA_SCOPE, FALLBACK_DATA_SCOPE));
    }

    /** 构造 GrantedAuthority：角色码加 {@code ROLE_} 前缀，权限码原样（供 {@code hasAuthority} 表达式使用）。 */
    private List<SimpleGrantedAuthority> authorities(LoginUser loginUser) {
        Set<String> codes = new LinkedHashSet<>();
        loginUser.roles().forEach(role -> codes.add(ROLE_PREFIX + role));
        codes.addAll(loginUser.perms());
        return codes.stream().map(SimpleGrantedAuthority::new).toList();
    }

    /** 取字符串集合声明；缺失或类型不符一律返回空集合（不抛异常，避免把"载荷缺失"误报为"令牌非法"）。 */
    private List<String> stringList(Claims claims, String name) {
        if (!(claims.get(name) instanceof List<?> raw)) {
            return List.of();
        }
        List<String> out = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item != null) {
                out.add(String.valueOf(item));
            }
        }
        return out;
    }

    /** 取长整型集合声明；JSON 数值可能被反序列化为 Integer/Long，统一按 {@link Number} 收口。 */
    private List<Long> longList(Claims claims, String name) {
        if (!(claims.get(name) instanceof List<?> raw)) {
            return List.of();
        }
        List<Long> out = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (item instanceof Number number) {
                out.add(number.longValue());
            }
        }
        return out;
    }

    /** 取整型声明；缺失或类型不符时返回兜底值（最严格口径）。 */
    private int intClaim(Claims claims, String name, int fallback) {
        return claims.get(name) instanceof Number number ? number.intValue() : fallback;
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        R<Void> body =
                R.fail(ErrorCode.UNAUTHORIZED.getCode(), ErrorCode.UNAUTHORIZED.getMessage());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }

    /** 是否会话入口端点（`/auth/login` 及其子路径、`/auth/refresh`）。 */
    private boolean isSessionEntry(String uri) {
        if (uri == null) {
            return false;
        }
        for (String path : SESSION_ENTRY_PATHS) {
            if (uri.equals(path) || uri.startsWith(path + "/")) {
                return true;
            }
        }
        return false;
    }
}
