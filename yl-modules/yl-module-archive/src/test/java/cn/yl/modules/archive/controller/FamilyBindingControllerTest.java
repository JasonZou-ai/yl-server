package cn.yl.modules.archive.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import cn.yl.api.security.Logical;
import cn.yl.api.security.LoginUser;
import cn.yl.api.security.PermissionCode;
import cn.yl.api.security.RequiresPermission;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.archive.dto.FamilyBindingCreateRequest;
import cn.yl.modules.archive.dto.FamilyBindingQuery;
import cn.yl.modules.archive.dto.FamilyBindingRejectRequest;
import cn.yl.modules.archive.service.FamilyBindingService;
import cn.yl.modules.archive.service.FamilyReportQueryService;
import java.lang.reflect.Method;
import java.util.Arrays;
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
 * 家属端控制器测试（D-4）——★C1 修复的机械验收断言 + 端点委托/鉴权收口。
 *
 * <p>核心：{@code approveRequiresOrgManage} 反射读 {@code approve} 的 {@link RequiresPermission}，钉死权限码 ==
 * {@code account:org:manage}（ORG_ADMIN 独占，FAMILY 无此码 → 第二闸即挡）。若有人把它改回 {@code account:family:bind}，
 * 本断言立即变红。
 */
@ExtendWith(MockitoExtension.class)
class FamilyBindingControllerTest {

    private static final long ADMIN_ID = 900000000000000002L;
    private static final long FAMILY_ID = 900000000000000004L;

    @Mock private FamilyBindingService bindingService;

    @Mock private FamilyReportQueryService reportQueryService;

    private FamilyBindingController controller;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        controller = new FamilyBindingController(bindingService, reportQueryService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ---------------- ★C1：approve 权限码本体订正 ----------------

    @Test
    void approveRequiresOrgManage() throws Exception {
        Method approve = FamilyBindingController.class.getMethod("approve", Long.class);
        RequiresPermission rp = approve.getAnnotation(RequiresPermission.class);

        assertNotNull(rp, "approve 必须声明 @RequiresPermission");
        assertArrayEquals(
                new String[] {PermissionCode.ACCOUNT_ORG_MANAGE},
                rp.value(),
                "★C1：approve 权限码须为 account:org:manage（ORG_ADMIN 独占）");
        assertFalse(
                Arrays.asList(rp.value()).contains(PermissionCode.ACCOUNT_FAMILY_BIND),
                "★C1：approve 不得再挂 account:family:bind（否则 FAMILY 可自我放行）");
    }

    @Test
    void unbindUsesOrLogicalWithSensitiveCode() throws Exception {
        Method unbind = FamilyBindingController.class.getMethod("unbind", Long.class);
        RequiresPermission rp = unbind.getAnnotation(RequiresPermission.class);

        assertNotNull(rp);
        assertEquals(Logical.OR, rp.logical(), "解绑须为 OR 语义（先例：AssessmentController#voidOrder）");
        List<String> codes = Arrays.asList(rp.value());
        assertTrue(codes.contains(PermissionCode.ACCOUNT_FAMILY_BIND));
        assertTrue(
                codes.contains(PermissionCode.ACCOUNT_FAMILY_UNBIND),
                "须含敏感码 account:family:unbind ⇒ PermissionAspect 强制第四闸");
    }

    // ---------------- 委托与鉴权收口 ----------------

    @Test
    void createDelegatesToServiceWithLoginUser() {
        LoginUser user = login(FAMILY_ID);
        FamilyBindingCreateRequest req = new FamilyBindingCreateRequest();

        controller.create(req);

        verify(bindingService).apply(req, user);
    }

    @Test
    void createRejectedWhenNotAuthenticated() {
        FamilyBindingCreateRequest req = new FamilyBindingCreateRequest();

        BizException ex = assertThrows(BizException.class, () -> controller.create(req));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
    }

    @Test
    void listDelegatesToService() {
        login(FAMILY_ID);
        FamilyBindingQuery query = new FamilyBindingQuery();

        controller.list(query);

        verify(bindingService).list(query);
    }

    @Test
    void rejectPassesReasonFromBody() {
        LoginUser user = login(FAMILY_ID);
        FamilyBindingRejectRequest req = new FamilyBindingRejectRequest();
        req.setReason("不同意");

        controller.reject(1L, req);

        verify(bindingService).reject(1L, "不同意", user);
    }

    @Test
    void rejectToleratesMissingBody() {
        LoginUser user = login(FAMILY_ID);

        controller.reject(1L, null);

        verify(bindingService).reject(1L, null, user);
    }

    @Test
    void unbindDelegatesToService() {
        LoginUser user = login(FAMILY_ID);

        controller.unbind(7L);

        verify(bindingService).unbind(7L, user);
    }

    @Test
    void approveDelegatesToService() {
        LoginUser user = login(ADMIN_ID);

        controller.approve(3L);

        verify(bindingService).approve(3L, user);
    }

    @Test
    void requireUserGuardUsedForEveryWrite() {
        // 无登录态时任一写端点都必须明确拒绝（不得以匿名身份落业务）
        assertThrows(BizException.class, () -> controller.approve(1L));
        assertThrows(BizException.class, () -> controller.reject(1L, null));
        assertThrows(BizException.class, () -> controller.unbind(1L));
        verify(bindingService, org.mockito.Mockito.never()).approve(any(), any());
    }

    // ---------------- 读端点：权限码 == report:view + 委托 ----------------

    @Test
    void readEndpointsRequireReportView() throws Exception {
        Method reports =
                FamilyBindingController.class.getMethod(
                        "reports", Long.class, FamilyBindingQuery.class);
        Method detail =
                FamilyBindingController.class.getMethod("reportDetail", Long.class, Long.class);
        Method trend = FamilyBindingController.class.getMethod("trend", Long.class);
        Method carePlan = FamilyBindingController.class.getMethod("carePlan", Long.class);

        for (Method m : List.of(reports, detail, trend, carePlan)) {
            RequiresPermission rp = m.getAnnotation(RequiresPermission.class);
            assertNotNull(rp, m.getName() + " 必须声明 @RequiresPermission");
            assertArrayEquals(
                    new String[] {PermissionCode.REPORT_VIEW},
                    rp.value(),
                    m.getName() + " 权限码须为 report:view");
        }
    }

    @Test
    void reportsDelegatesToQueryService() {
        FamilyBindingQuery query = new FamilyBindingQuery();

        controller.reports(7L, query);

        verify(reportQueryService).reports(7L, query);
    }

    @Test
    void reportDetailDelegatesToQueryService() {
        controller.reportDetail(7L, 11L);

        verify(reportQueryService).detail(7L, 11L);
    }

    @Test
    void trendDelegatesToQueryService() {
        controller.trend(7L);

        verify(reportQueryService).trend(7L);
    }

    @Test
    void carePlanDelegatesToQueryService() {
        controller.carePlan(7L);

        verify(reportQueryService).carePlan(7L);
    }

    private LoginUser login(long userId) {
        LoginUser user =
                new LoginUser(
                        userId,
                        1L,
                        List.of("FAMILY"),
                        List.of("account:family:bind"),
                        List.of(1L),
                        5);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        return user;
    }
}
