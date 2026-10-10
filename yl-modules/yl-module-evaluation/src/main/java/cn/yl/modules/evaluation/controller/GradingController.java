package cn.yl.modules.evaluation.controller;

import cn.yl.api.security.PermissionCode;
import cn.yl.api.security.RequiresPermission;
import cn.yl.common.api.R;
import cn.yl.modules.evaluation.dto.GradingRequest;
import cn.yl.modules.evaluation.engine.GradeResult;
import cn.yl.modules.evaluation.engine.RuleMeta;
import cn.yl.modules.evaluation.engine.SpecialFlags;
import cn.yl.modules.evaluation.service.AssessmentGradingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评估分级接口（C2 / GB/T 42195-2022 分级规则引擎接入点）。
 *
 * <p>{@code POST /grade} 依据 26 项作答与特殊标志输出等级判定；{@code GET /rule-meta} 返回当前生效规则版本指纹。
 *
 * <p><b>鉴权（CR-M2-006 同批）</b>：两个端点原均无第二闸。取值按「最小够用」： {@code /grade} 是无状态试算（不落库），与录入同权（{@code
 * evaluation:item:input}）；{@code /rule-meta} 属国标规则只读， 取 {@code
 * rule:view}（五角色全授予，与「登录即可读规则」的既定口径一致）。
 */
@RestController
@RequestMapping("/api/v1/evaluation")
@RequiredArgsConstructor
public class GradingController {

    private final AssessmentGradingService gradingService;

    @PostMapping("/grade")
    @RequiresPermission(PermissionCode.EVAL_ITEM_INPUT)
    public R<GradeResult> grade(@Valid @RequestBody GradingRequest request) {
        SpecialFlags flags = request.getFlags() == null ? new SpecialFlags() : request.getFlags();
        GradeResult result = gradingService.grade(request.getAnswers(), flags);
        return R.ok(result);
    }

    @GetMapping("/rule-meta")
    @RequiresPermission(PermissionCode.RULE_VIEW)
    public R<RuleMeta> ruleMeta() {
        return R.ok(gradingService.meta());
    }
}
