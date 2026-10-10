package cn.yl.modules.archive.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

import cn.yl.api.security.LoginUser;
import cn.yl.api.security.PermissionCode;
import cn.yl.api.security.RequiresPermission;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.archive.dto.ElderCreateRequest;
import cn.yl.modules.archive.dto.ElderQuery;
import cn.yl.modules.archive.dto.UserBindingRequest;
import cn.yl.modules.archive.service.ElderArchiveService;
import cn.yl.modules.archive.service.ElderUserBindingService;
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
 * 档案控制器测试（D-2）：固定「操作者与归属机构不采信请求体」的鉴权收口口径。
 *
 * <p>与 {@code AssessmentControllerTest} 同构：① {@code orgId} 缺省取登录态默认机构，显式越域须拒绝； ②
 * 服务收到的操作者是登录态本人，与请求体无关；③ 无登录态不得以匿名身份落业务。D-4 追加 {@code user-binding} 端点（权限码 {@code
 * account:org:manage}）。
 */
@ExtendWith(MockitoExtension.class)
class ElderControllerTest {

    private static final long ADMIN_ID = 900000000000000002L;

    @Mock private ElderArchiveService service;

    @Mock private ElderUserBindingService userBindingService;

    private ElderController controller;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        controller = new ElderController(service, userBindingService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    /** 请求体未给机构时，归属机构取登录态默认机构。 */
    @Test
    void createDefaultsOrgToLoginUserOrg() {
        LoginUser user = login(ADMIN_ID);
        ElderCreateRequest req = new ElderCreateRequest();
        req.setName("张三");

        controller.create(req);

        assertEquals(1L, req.getOrgId(), "缺省机构应取登录态默认机构");
        verify(service).create(req, user);
    }

    /** 显式指定的机构越出本人机构域时必须拒绝（否则该行在第三闸下连创建者自己也查不到）。 */
    @Test
    void createRejectsOrgOutsideDataScope() {
        login(ADMIN_ID);
        ElderCreateRequest req = new ElderCreateRequest();
        req.setName("张三");
        req.setOrgId(99L);

        BizException ex = assertThrows(BizException.class, () -> controller.create(req));

        assertEquals(ErrorCode.ROLE_NOT_PERMITTED.getCode(), ex.getCode());
    }

    /** 操作者一律取登录态本人（请求体不承载身份）。 */
    @Test
    void createUsesLoginUserAsOperator() {
        LoginUser user = login(ADMIN_ID);
        ElderCreateRequest req = new ElderCreateRequest();
        req.setName("张三");
        req.setOrgId(1L);

        controller.create(req);

        verify(service).create(req, user);
    }

    /** 列表交给服务（可见范围由第三闸收口，控制器不再补充机构过滤）。 */
    @Test
    void listDelegatesToService() {
        login(ADMIN_ID);
        ElderQuery query = new ElderQuery();

        controller.list(query);

        verify(service).list(query);
    }

    /** 详情交给服务（越界与不存在同码由服务层统一为 10004）。 */
    @Test
    void detailDelegatesToService() {
        login(ADMIN_ID);

        controller.detail(12345L);

        verify(service).detail(12345L);
    }

    /** 无登录态时不得以匿名身份落业务，必须明确拒绝。 */
    @Test
    void createRejectedWhenNotAuthenticated() {
        ElderCreateRequest req = new ElderCreateRequest();
        req.setName("张三");

        BizException ex = assertThrows(BizException.class, () -> controller.create(req));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
    }

    // ---------------- D-4：elder↔user 绑定端点 ----------------

    /** 权限码钉死 {@code account:org:manage}（ORG_ADMIN 独占）。 */
    @Test
    void bindUserRequiresOrgManage() throws Exception {
        Method bindUser =
                ElderController.class.getMethod("bindUser", Long.class, UserBindingRequest.class);
        RequiresPermission rp = bindUser.getAnnotation(RequiresPermission.class);

        org.junit.jupiter.api.Assertions.assertNotNull(rp, "bindUser 必须声明 @RequiresPermission");
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                new String[] {PermissionCode.ACCOUNT_ORG_MANAGE},
                rp.value(),
                "user-binding 权限码须为 account:org:manage");
    }

    /** 委托服务，操作者取登录态本人。 */
    @Test
    void bindUserDelegatesToServiceWithLoginUser() {
        LoginUser user = login(ADMIN_ID);
        UserBindingRequest req = new UserBindingRequest();
        req.setUserId(900000000000000003L);

        controller.bindUser(500L, req);

        verify(userBindingService).bind(500L, 900000000000000003L, user);
    }

    /** 无登录态不得以匿名身份落业务。 */
    @Test
    void bindUserRejectedWhenNotAuthenticated() {
        UserBindingRequest req = new UserBindingRequest();
        req.setUserId(900000000000000003L);

        BizException ex = assertThrows(BizException.class, () -> controller.bindUser(500L, req));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
    }

    private LoginUser login(long userId) {
        LoginUser user = new LoginUser(userId, 1L, List.of("ORG_ADMIN"), List.of(), List.of(1L), 2);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
        return user;
    }
}
