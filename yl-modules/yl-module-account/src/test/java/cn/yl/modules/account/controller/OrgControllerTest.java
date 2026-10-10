package cn.yl.modules.account.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import cn.yl.api.security.Logical;
import cn.yl.api.security.PermissionCode;
import cn.yl.api.security.RequiresPermission;
import cn.yl.common.api.PageResult;
import cn.yl.modules.account.dto.OrgQuery;
import cn.yl.modules.account.dto.OrgView;
import cn.yl.modules.account.service.OrgQueryService;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** R6 机构控制器测试：委托 + 两端点均声明 OR 权限（防"注解漏写"）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrgControllerTest {

    @Mock private OrgQueryService orgQueryService;

    private OrgController controller;

    @BeforeEach
    void setUp() {
        controller = new OrgController(orgQueryService);
    }

    @Test
    void listDelegatesToService() {
        OrgView view = new OrgView();
        view.setId(1L);
        when(orgQueryService.list(new OrgQuery()))
                .thenReturn(PageResult.of(List.of(view), 1L, 1, 10));

        PageResult<OrgView> result = controller.list(new OrgQuery()).getData();

        assertEquals(1L, result.getTotal());
    }

    @Test
    void detailDelegatesToService() {
        OrgView view = new OrgView();
        view.setId(1L);
        when(orgQueryService.detail(1L)).thenReturn(view);

        assertEquals(1L, controller.detail(1L).getData().getId());
    }

    @Test
    void listDeclaresOrgPermissionsWithOrSemantics() throws Exception {
        Method method = OrgController.class.getMethod("list", OrgQuery.class);
        RequiresPermission permission = method.getAnnotation(RequiresPermission.class);

        assertNotNull(permission);
        assertEquals(Logical.OR, permission.logical());
        assertTrue(Arrays.asList(permission.value()).contains(PermissionCode.ACCOUNT_ORG_MANAGE));
        assertTrue(
                Arrays.asList(permission.value()).contains(PermissionCode.ACCOUNT_ORG_MANAGE_READ));
    }

    @Test
    void detailDeclaresOrgPermissionsWithOrSemantics() throws Exception {
        Method method = OrgController.class.getMethod("detail", Long.class);
        RequiresPermission permission = method.getAnnotation(RequiresPermission.class);

        assertNotNull(permission);
        assertEquals(Logical.OR, permission.logical());
    }
}
