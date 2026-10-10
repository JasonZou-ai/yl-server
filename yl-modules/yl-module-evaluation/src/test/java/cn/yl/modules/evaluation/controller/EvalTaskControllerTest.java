package cn.yl.modules.evaluation.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import cn.yl.api.security.PermissionCode;
import cn.yl.api.security.RequiresPermission;
import cn.yl.common.api.PageResult;
import cn.yl.modules.evaluation.dto.EvalTaskQuery;
import cn.yl.modules.evaluation.dto.EvalTaskView;
import cn.yl.modules.evaluation.service.EvalTaskQueryService;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** B6 评估任务列表控制器测试：① 委托服务并原样返回分页；② 端点在第二闸声明 {@code evaluation:task:read}（防"注解漏写"）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class EvalTaskControllerTest {

    @Mock private EvalTaskQueryService evalTaskQueryService;

    private EvalTaskController controller;

    @BeforeEach
    void setUp() {
        controller = new EvalTaskController(evalTaskQueryService);
    }

    @Test
    void listDelegatesToServiceAndReturnsPage() {
        EvalTaskQuery query = new EvalTaskQuery();
        EvalTaskView view = new EvalTaskView();
        view.setId(9001L);
        view.setOrgId(1L);
        when(evalTaskQueryService.list(query)).thenReturn(PageResult.of(List.of(view), 1L, 1, 10));

        PageResult<EvalTaskView> result = controller.list(query).getData();

        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getList().size());
        assertEquals(9001L, result.getList().get(0).getId());
    }

    @Test
    void listDeclaresEvalTaskReadPermission() throws Exception {
        Method method = EvalTaskController.class.getMethod("list", EvalTaskQuery.class);
        RequiresPermission permission = method.getAnnotation(RequiresPermission.class);

        assertTrue(permission != null, "B6 列表端点必须声明第二闸权限");
        assertEquals(List.of(PermissionCode.EVAL_TASK_READ), List.of(permission.value()));
    }
}
