package cn.yl.modules.evaluation.controller;

import cn.yl.api.security.PermissionCode;
import cn.yl.api.security.RequiresPermission;
import cn.yl.common.api.PageResult;
import cn.yl.common.api.R;
import cn.yl.modules.evaluation.dto.EvalTaskQuery;
import cn.yl.modules.evaluation.dto.EvalTaskView;
import cn.yl.modules.evaluation.service.EvalTaskQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B6 评估任务列表（{@code GET /api/v1/evaluation/tasks}）。
 *
 * <p>权限码 {@code evaluation:task:read}（2020 → ASSESSOR/ORG_ADMIN/SUPERVISOR）。可见范围<b>不靠权限点区分</b>，由第三闸
 * {@code data_scope} 行级收口（机构管理员/评估员=本机构；监管=只读全局），故服务层不手写机构过滤。
 */
@RestController
@RequestMapping("/api/v1/evaluation/tasks")
@RequiredArgsConstructor
public class EvalTaskController {

    private final EvalTaskQueryService evalTaskQueryService;

    /** 分页查询评估任务（按机构/评估员/状态筛选）。 */
    @GetMapping
    @RequiresPermission(PermissionCode.EVAL_TASK_READ)
    public R<PageResult<EvalTaskView>> list(EvalTaskQuery query) {
        return R.ok(evalTaskQueryService.list(query));
    }
}
