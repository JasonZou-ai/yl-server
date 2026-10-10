package cn.yl.modules.evaluation.controller;

import cn.yl.api.security.Logical;
import cn.yl.api.security.PermissionCode;
import cn.yl.api.security.RequiresPermission;
import cn.yl.common.api.R;
import cn.yl.modules.evaluation.dto.ItemBankResponse;
import cn.yl.modules.evaluation.service.AssessmentService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 题库下发接口（C1 配套）：{@code GET /api/v1/evaluation/items}。
 *
 * <p>返回 26 项二级指标元信息（itemId 即引擎指标编号 101~405）与所属规则版本指纹。四端据此免除端上 「本地题号 → 指标
 * id」的硬编码映射：口径变更（增删题、改满分）时只需服务端更新 eval_item， 端上按 ruleSha 变化重新拉取即可。
 *
 * <p><b>鉴权（CR-M2-006 同批）</b>：题库属评估域元数据，原实现无第二闸（低敏故风险低，但违反「每个端点显式声明权限码」的口径）。 现要求 {@code
 * evaluation:task:read} 或 {@code evaluation:item:input} 任一 —— 录入方与任务查看方均可读。字典类表不参与第三闸 过滤（{@code
 * eval_item} 未登记数据域列，须对所有账号可读）。
 */
@RestController
@RequestMapping("/api/v1/evaluation")
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification =
                                "AssessmentService 是 Spring 容器管理的无状态单例 Bean，构造器注入是标准 DI 用法"))
public class EvaluationItemController {

    private final AssessmentService assessmentService;

    /** 题库元信息（26 项）。 */
    @GetMapping("/items")
    @RequiresPermission(
            value = {PermissionCode.EVAL_TASK_READ, PermissionCode.EVAL_ITEM_INPUT},
            logical = Logical.OR)
    public R<ItemBankResponse> items() {
        return R.ok(assessmentService.items());
    }
}
