package cn.yl.modules.evaluation.dto;

import lombok.Data;

/**
 * B6 评估任务列表查询入参（{@code GET /api/v1/evaluation/tasks}）。
 *
 * <p>分页缺省由服务层兜底；{@code assessorId}/{@code status} 为可选过滤条件，均<b>不含机构字段</b> —— 可见范围由第三闸数据域 （{@code
 * eval_task.org_id IN (orgIds)} / {@code assessor_id = me}）自动收口。
 */
@Data
public class EvalTaskQuery {

    private Integer pageNum;
    private Integer pageSize;
    private Long assessorId;
    private Integer status;
}
