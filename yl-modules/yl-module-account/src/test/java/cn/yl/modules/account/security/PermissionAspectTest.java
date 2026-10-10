package cn.yl.modules.account.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yl.api.security.Logical;
import cn.yl.api.security.LoginUser;
import cn.yl.api.security.PermissionCode;
import cn.yl.api.security.RequiresPermission;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 权限码校验切面测试（四道闸·第二闸 + 敏感点联动第四闸）——CR-M2-002 越权回归。
 *
 * <p>收口背景：{@code AssessmentController} 此前无任何方法级鉴权，操作者取自请求体，实测"评估员令牌传机构管理员 ID 即可发布他人评估单"。
 * 本切面是该缺口的**拦截点**，故必须有独立回归：无权限必须拒、有权限必须放、敏感点必须叠加第四闸。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PermissionAspectTest {

    private static final long ADMIN_ID = 900000000000000002L;
    private static final long ASSESSOR_ID = 900000000000000001L;

    @Mock private SecondVerifyGuard secondVerifyGuard;
    @Mock private ArchiveAccessRateGuard archiveAccessRateGuard;
    @Mock private RequiresPermission requiresPermission;

    private PermissionAspect aspect;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        aspect = new PermissionAspect(secondVerifyGuard, archiveAccessRateGuard);
        when(requiresPermission.logical()).thenReturn(Logical.ALL);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 越权例 1：账号无所需权限码（如评估员调复核发布）→ 明确拒绝。 */
    @Test
    void deniedWhenPermissionMissing() {
        login(ASSESSOR_ID, List.of("ASSESSOR"), List.of("evaluation:item:input"), 2);
        when(requiresPermission.value())
                .thenReturn(new String[] {PermissionCode.EVAL_ORDER_REVIEW});

        BizException ex =
                assertThrows(BizException.class, () -> aspect.checkPermission(requiresPermission));

        assertEquals(ErrorCode.ROLE_NOT_PERMITTED.getCode(), ex.getCode());
    }

    /** 越权例 2：账号持有权限码（如机构管理员）→ 放行，并委派档案访问异常检测（只告警不拦截）。 */
    @Test
    void grantedWhenPermissionPresent() {
        login(ADMIN_ID, List.of("ORG_ADMIN"), List.of(PermissionCode.EVAL_ORDER_REVIEW), 2);
        when(requiresPermission.value())
                .thenReturn(new String[] {PermissionCode.EVAL_ORDER_REVIEW});

        assertDoesNotThrow(() -> aspect.checkPermission(requiresPermission));

        verify(archiveAccessRateGuard)
                .onPermissionGranted(any(LoginUser.class), any(String[].class));
    }

    /** 越权例 3：未建立登录态（无令牌 / 令牌非法）→ 未登录拒绝，而不是"无权限"。 */
    @Test
    void deniedWhenNotAuthenticated() {
        when(requiresPermission.value())
                .thenReturn(new String[] {PermissionCode.EVAL_ORDER_REVIEW});

        BizException ex =
                assertThrows(BizException.class, () -> aspect.checkPermission(requiresPermission));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
    }

    /** 越权例 4：敏感权限点（作废）缺第四闸二次验证凭据 → 拒绝，即使权限码已授予。 */
    @Test
    void sensitivePermissionRequiresSecondVerify() {
        login(ADMIN_ID, List.of("ORG_ADMIN"), List.of(PermissionCode.EVAL_ORDER_VOID), 2);
        when(requiresPermission.value()).thenReturn(new String[] {PermissionCode.EVAL_ORDER_VOID});
        when(secondVerifyGuard.verify(any(), anyLong())).thenReturn(false);

        BizException ex =
                assertThrows(BizException.class, () -> aspect.checkPermission(requiresPermission));

        assertEquals(ErrorCode.SECOND_VERIFY_REQUIRED.getCode(), ex.getCode());
    }

    /** 越权例 5：敏感权限点持有效二次验证凭据 → 放行（第四闸通过后才落到业务）。 */
    @Test
    void sensitivePermissionPassesWithSecondVerifyToken() {
        login(ADMIN_ID, List.of("ORG_ADMIN"), List.of(PermissionCode.EVAL_ORDER_VOID), 2);
        when(requiresPermission.value()).thenReturn(new String[] {PermissionCode.EVAL_ORDER_VOID});
        when(secondVerifyGuard.verify(any(), anyLong())).thenReturn(true);

        assertDoesNotThrow(() -> aspect.checkPermission(requiresPermission));
    }

    /** 越权例 6：OR 逻辑——多权限码任一命中即放行（用于"权限码从宽、数据域从严"的读类端点）。 */
    @Test
    void orLogicalGrantsWhenAnyPermissionPresent() {
        login(ASSESSOR_ID, List.of("ASSESSOR"), List.of(PermissionCode.EVAL_TASK_READ), 2);
        when(requiresPermission.logical()).thenReturn(Logical.OR);
        when(requiresPermission.value())
                .thenReturn(
                        new String[] {
                            PermissionCode.EVAL_ORDER_REVIEW_READ, PermissionCode.EVAL_TASK_READ
                        });

        assertDoesNotThrow(() -> aspect.checkPermission(requiresPermission));
    }

    private void login(long userId, List<String> roles, List<String> perms, int dataScope) {
        LoginUser user = new LoginUser(userId, 1L, roles, perms, List.of(1L), dataScope);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
