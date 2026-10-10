package cn.yl.modules.evaluation.service;

import cn.yl.common.api.PageResult;
import cn.yl.modules.evaluation.domain.entity.EvalTask;
import cn.yl.modules.evaluation.dto.EvalTaskQuery;
import cn.yl.modules.evaluation.dto.EvalTaskView;
import cn.yl.modules.evaluation.mapper.EvalTaskMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * B6 评估任务查询服务。
 *
 * <p><b>数据域口径</b>：本方法<b>不手写 {@code org_id} 过滤</b> —— 可见范围由第三闸 {@code DataScopePermissionHandler}
 * 按登录态自动收口（scope=2 → {@code eval_task.org_id IN (orgIds)}；scope=1 → {@code eval_task.assessor_id =
 * me}； scope=4 监管 → 不限）。{@code eval_task} 已双登记（{@code org_id} + {@code assessor_id}），故无需服务层兜底。
 *
 * <p>{@code eval_task} 有 {@code deleted} 列，查询必须显式 {@code deleted = 0}（实体映射含该列）。
 */
@Service
@RequiredArgsConstructor
public class EvalTaskQueryService {

    private static final int DEFAULT_PAGE_NUM = 1;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int DELETED_NO = 0;

    private final EvalTaskMapper evalTaskMapper;

    /**
     * 分页查询评估任务。
     *
     * @param query 查询条件（可空）
     * @return 分页结果
     */
    public PageResult<EvalTaskView> list(EvalTaskQuery query) {
        EvalTaskQuery q = query == null ? new EvalTaskQuery() : query;
        int pageNum =
                q.getPageNum() == null || q.getPageNum() < 1 ? DEFAULT_PAGE_NUM : q.getPageNum();
        int pageSize =
                q.getPageSize() == null || q.getPageSize() < 1
                        ? DEFAULT_PAGE_SIZE
                        : q.getPageSize();

        QueryWrapper<EvalTask> wrapper = new QueryWrapper<>();
        wrapper.eq("deleted", DELETED_NO);
        if (q.getAssessorId() != null) {
            wrapper.eq("assessor_id", q.getAssessorId());
        }
        if (q.getStatus() != null) {
            wrapper.eq("status", q.getStatus());
        }
        wrapper.orderByDesc("created_at");

        Page<EvalTask> page = evalTaskMapper.selectPage(new Page<>(pageNum, pageSize), wrapper);
        List<EvalTaskView> list = page.getRecords().stream().map(this::toView).toList();
        return PageResult.of(list, page.getTotal(), pageNum, pageSize);
    }

    private EvalTaskView toView(EvalTask task) {
        EvalTaskView view = new EvalTaskView();
        view.setId(task.getId());
        view.setTaskNo(task.getTaskNo());
        view.setElderId(task.getElderId());
        view.setOrgId(task.getOrgId());
        view.setAssessorId(task.getAssessorId());
        view.setScene(task.getScene());
        view.setStatus(task.getStatus());
        view.setDueAt(task.getDueAt());
        view.setCreatedAt(task.getCreatedAt());
        return view;
    }
}
