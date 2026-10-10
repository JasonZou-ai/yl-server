package cn.yl.modules.evaluation.controller;

import cn.yl.api.security.PermissionCode;
import cn.yl.api.security.RequiresPermission;
import cn.yl.common.api.R;
import cn.yl.modules.evaluation.dto.RuleMappingView;
import cn.yl.modules.evaluation.service.RuleMappingService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * B7 国标条款→规则条目映射（{@code GET /api/v1/rule/mapping}）。
 *
 * <p>权限码 {@code rule:view}（2022 → 五角色全授予）。{@code gb_rule_mapping} 为字典类表，数据域 mode=all（无行级过滤）。
 */
@RestController
@RequestMapping("/api/v1/rule")
@RequiredArgsConstructor
public class RuleMappingController {

    private final RuleMappingService ruleMappingService;

    /** 条款→规则条目映射矩阵（可机器校验的追溯锚点）。 */
    @GetMapping("/mapping")
    @RequiresPermission(PermissionCode.RULE_VIEW)
    public R<List<RuleMappingView>> mapping(@RequestParam Long ruleVersionId) {
        return R.ok(ruleMappingService.mapping(ruleVersionId));
    }
}
