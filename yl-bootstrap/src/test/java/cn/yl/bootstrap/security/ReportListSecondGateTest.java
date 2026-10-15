package cn.yl.bootstrap.security;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import cn.yl.api.security.Logical;
import cn.yl.api.security.LoginUser;
import cn.yl.api.security.PermissionCode;
import cn.yl.api.security.RequiresPermission;
import cn.yl.common.api.PageQuery;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.account.security.ArchiveAccessRateGuard;
import cn.yl.modules.account.security.PermissionAspect;
import cn.yl.modules.account.security.SecondVerifyGuard;
import cn.yl.modules.evaluation.controller.ReportListController;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 「本人报告列表」第二闸行为回归（rR1a6L B5-后端）。
 *
 * <p>为什么单开一个类：<b>无 {@code report:view} ⇒ 20003</b> 这条负例在真实环境里无法用种子账号复现 ——
 * 种子里五个角色<strong>全部</strong>持有 2001（{@code 03_seed_rbac.sql} 3001–3005），故 E2E 冒烟造不出 「缺该码的账号」（造号 =
 * 改种子，属红线）。这里用 {@link ReportListController} 上<strong>真实</strong>的 {@link RequiresPermission}
 * 注解驱动真实的 {@link PermissionAspect}，把「注解声明的码 → 第二闸判定」这一整段钉死： 注解被摘、被换成其它码、或切面逻辑被放宽，本类即变红。
 */
@ExtendWith(MockitoExtension.class)
class ReportListSecondGateTest {

    @Mock private SecondVerifyGuard secondVerifyGuard;

    @Mock private ArchiveAccessRateGuard archiveAccessRateGuard;

    private PermissionAspect aspect;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        aspect = new PermissionAspect(secondVerifyGuard, archiveAccessRateGuard);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 端点声明的权限码 == 契约 {@code x-required-permission: report:view}（且为 ALL 语义）。 */
    @Test
    void endpointDeclaresReportView() {
        RequiresPermission declared = declaredOnList();

        assertArrayEquals(
                new String[] {PermissionCode.REPORT_VIEW},
                declared.value(),
                "报告列表端点的权限码须为 report:view，与契约同源");
        assertEquals(Logical.ALL, declared.logical());
    }

    /** 负例：登录态缺 {@code report:view} ⇒ 第二闸即挡，业务码 {@code 20003}。 */
    @Test
    void deniedWith20003WhenReportViewMissing() {
        login(List.of("ELDER"), List.of());

        BizException ex =
                assertThrows(BizException.class, () -> aspect.checkPermission(declaredOnList()));

        assertEquals(ErrorCode.ROLE_NOT_PERMITTED.getCode(), ex.getCode());
    }

    /** 正例：ELDER 持 {@code report:view}（种子 3001）⇒ 放行；可见范围另由第三闸收口。 */
    @Test
    void grantedWhenReportViewPresent() {
        login(List.of("ELDER"), List.of(PermissionCode.REPORT_VIEW));

        assertDoesNotThrow(() -> aspect.checkPermission(declaredOnList()));
    }

    private RequiresPermission declaredOnList() {
        try {
            Method list = ReportListController.class.getMethod("list", PageQuery.class);
            RequiresPermission declared = list.getAnnotation(RequiresPermission.class);
            assertNotNull(declared, "报告列表端点必须声明 @RequiresPermission");
            return declared;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读取 ReportListController#list 注解失败", e);
        }
    }

    private void login(List<String> roles, List<String> perms) {
        LoginUser user = new LoginUser(900000000000000001L, 1L, roles, perms, List.of(1L), 1);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
