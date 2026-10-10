package cn.yl.api.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yl.common.security.TokenBlacklistStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 统一鉴权过滤器测试（缺陷 D1 会话入口回归 + CR-M2-002 载荷完整装配）。
 *
 * <p><b>D1 现场</b>：端上已以「评估员」登录，再调用 {@code /auth/login/ANDROID} 切换为「机构管理员」时，
 * 过滤器用旧令牌建立了旧身份的安全上下文，数据域过滤器随即给登录自身的 {@code sys_user} 查询追加 {@code AND (sys_user.id = 旧用户)}，SQL
 * 查不到目标账号 → 返回「账号不存在」，**复核人无法切换登录**。口径：会话入口端点必须不解析、不继承既有令牌。
 *
 * <p><b>CR-M2-002 口径</b>：业务端点必须把 {@code perms / orgIds / ds} 三项载荷完整装配进 {@link
 * LoginUser}；载荷缺失时按最严格口径兜底 （dataScope=1、perms 空），即"失败即拒"，绝不放行。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class JwtAuthFilterTest {

    @Mock private JwtTokenProvider tokenProvider;
    @Mock private Claims claims;
    @Mock private TokenBlacklistStore blacklistStore;

    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        // 黑名单默认未命中（mock 返回 false）→ 既有断言行为不变
        filter = new JwtAuthFilter(tokenProvider, new ObjectMapper(), blacklistStore);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void loginEndpointIgnoresExistingTokenAndKeepsContextEmpty() throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("POST", "/api/v1/auth/login/ANDROID");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer stale-or-valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        verify(tokenProvider, never()).parse(anyString());
        assertNull(
                SecurityContextHolder.getContext().getAuthentication(),
                "登录端点不得继承既有登录态（否则数据域过滤器会用旧身份限制 sys_user 查询）");
        assertNull(response.getContentType(), "会话入口端点不应被过滤器短路为 401");
    }

    @Test
    void refreshEndpointIgnoresExistingToken() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/auth/refresh");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer any-token");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(tokenProvider, never()).parse(anyString());
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void businessEndpointStillEstablishesLoginUser() throws Exception {
        stubToken("good-token");

        LoginUser user = doFilterAndGetLoginUser("good-token");

        assertNotNull(user, "业务端点必须建立登录态");
        verify(tokenProvider).parse("good-token");
        assertEquals(900000000000000001L, user.userId());
    }

    /** CR-M2-002 核心：权限码 / 归属机构 / 数据域三项载荷必须完整装配（否则第二闸全量 403、第三闸失效）。 */
    @Test
    void businessEndpointAssemblesPermsOrgIdsAndDataScope() throws Exception {
        when(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class))
                .thenReturn(JwtTokenProvider.TYPE_ACCESS);
        when(claims.get(JwtTokenProvider.CLAIM_USER_ID, Number.class))
                .thenReturn(900000000000000002L);
        when(claims.get(JwtTokenProvider.CLAIM_ORG_ID, Number.class)).thenReturn(1L);
        when(claims.get(JwtTokenProvider.CLAIM_ROLES)).thenReturn(List.of("ORG_ADMIN"));
        when(claims.get(JwtTokenProvider.CLAIM_PERMS))
                .thenReturn(List.of("evaluation:order:review", "evaluation:order:void"));
        when(claims.get(JwtTokenProvider.CLAIM_ORG_IDS)).thenReturn(List.of(1L));
        when(claims.get(JwtTokenProvider.CLAIM_DATA_SCOPE)).thenReturn(2);
        when(tokenProvider.parse("admin-token")).thenReturn(claims);

        MockHttpServletRequest request =
                new MockHttpServletRequest(
                        "POST", "/api/v1/evaluation/assessments/7001/review/publish");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer admin-token");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        assertNotNull(auth);
        LoginUser user = (LoginUser) auth.getPrincipal();
        assertEquals(2, user.dataScope(), "机构管理员数据域应为 2（本机构）");
        assertEquals(List.of(1L), user.orgIds(), "归属机构集合须装配，否则 scope=2 退化为恒假");
        assertTrue(user.hasPermission("evaluation:order:review"), "复核权限码须装配");
        assertTrue(user.hasPermission("evaluation:order:void"), "作废权限码须装配");
        // 权限码同时登记为 GrantedAuthority，使 hasAuthority(..) 表达式同样可用
        assertTrue(
                auth.getAuthorities().stream()
                        .anyMatch(a -> "evaluation:order:review".equals(a.getAuthority())),
                "权限码须登记为 GrantedAuthority");
        assertTrue(
                auth.getAuthorities().stream()
                        .anyMatch(a -> "ROLE_ORG_ADMIN".equals(a.getAuthority())),
                "角色须保留 ROLE_ 前缀");
    }

    /** 载荷缺失（例如改造前签发的旧令牌）→ 按最严格口径兜底：perms 空 + dataScope=1，失败即拒。 */
    @Test
    void missingPayloadFallsBackToStrictestScope() throws Exception {
        when(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class))
                .thenReturn(JwtTokenProvider.TYPE_ACCESS);
        when(claims.get(JwtTokenProvider.CLAIM_USER_ID, Number.class)).thenReturn(101L);
        when(claims.get(JwtTokenProvider.CLAIM_ORG_ID, Number.class)).thenReturn(null);
        when(claims.get(JwtTokenProvider.CLAIM_ROLES)).thenReturn(List.of("ASSESSOR"));
        when(claims.get(JwtTokenProvider.CLAIM_PERMS)).thenReturn(null);
        when(claims.get(JwtTokenProvider.CLAIM_ORG_IDS)).thenReturn(null);
        when(claims.get(JwtTokenProvider.CLAIM_DATA_SCOPE)).thenReturn(null);
        when(tokenProvider.parse("legacy-token")).thenReturn(claims);

        LoginUser user = doFilterAndGetLoginUser("legacy-token");

        assertNotNull(user);
        assertTrue(user.perms().isEmpty(), "载荷缺失时权限码必须为空（不猜测、不放行）");
        assertTrue(user.orgIds().isEmpty());
        assertEquals(1, user.dataScope(), "载荷缺失时数据域必须退化为 1（仅本人）");
    }

    /** 非 access 令牌（refresh）不得用于业务接口。 */
    @Test
    void refreshTokenRejectedForBusinessEndpoint() throws Exception {
        when(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class))
                .thenReturn(JwtTokenProvider.TYPE_REFRESH);
        when(tokenProvider.parse("refresh-token")).thenReturn(claims);

        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/evaluation/assessments/7001/report");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer refresh-token");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertNull(SecurityContextHolder.getContext().getAuthentication(), "refresh 令牌不得建立业务登录态");
    }

    /**
     * D-4 登出黑名单：{@code parse} 成功后命中黑名单即早短路为未认证（401 + {@code R.code=10002}），不装配任何身份。
     *
     * <p>钉住 {@code authLogout} 的核心可用性断言：登出后旧 access 令牌立即失效。
     */
    @Test
    void blacklistedTokenShortCircuitsToUnauthorized() throws Exception {
        stubToken("revoked-token");
        when(blacklistStore.isBlacklisted("revoked-token")).thenReturn(true);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/elders");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer revoked-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus(), "命中黑名单必须短路为 401");
        assertNull(SecurityContextHolder.getContext().getAuthentication(), "命中黑名单不得装配任何身份（等同未认证）");
        assertTrue(response.getContentAsString().contains("10002"), "响应体须为 R.code=10002");
        verify(tokenProvider).parse("revoked-token");
    }

    /** 未命中黑名单：行为与既有完全一致（正常装配登录态）。 */
    @Test
    void nonBlacklistedTokenStillEstablishesLoginUser() throws Exception {
        stubToken("live-token");
        when(blacklistStore.isBlacklisted("live-token")).thenReturn(false);

        LoginUser user = doFilterAndGetLoginUser("live-token");

        assertNotNull(user, "未命中黑名单时正常装配登录态");
    }

    private void stubToken(String token) {
        when(claims.get(JwtTokenProvider.CLAIM_TYPE, String.class))
                .thenReturn(JwtTokenProvider.TYPE_ACCESS);
        when(claims.get(JwtTokenProvider.CLAIM_USER_ID, Number.class))
                .thenReturn(900000000000000001L);
        when(claims.get(JwtTokenProvider.CLAIM_ORG_ID, Number.class)).thenReturn(null);
        when(claims.get(JwtTokenProvider.CLAIM_ROLES)).thenReturn(List.of("ASSESSOR"));
        when(claims.get(JwtTokenProvider.CLAIM_PERMS)).thenReturn(List.of("evaluation:task:read"));
        when(claims.get(JwtTokenProvider.CLAIM_ORG_IDS)).thenReturn(List.of(1L));
        when(claims.get(JwtTokenProvider.CLAIM_DATA_SCOPE)).thenReturn(2);
        when(tokenProvider.parse(token)).thenReturn(claims);
    }

    private LoginUser doFilterAndGetLoginUser(String token) throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest("GET", "/api/v1/evaluation/assessments/7001/report");
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth == null ? null : (LoginUser) auth.getPrincipal();
    }
}
