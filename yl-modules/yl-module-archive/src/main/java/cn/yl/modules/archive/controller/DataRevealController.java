package cn.yl.modules.archive.controller;

import cn.yl.api.security.Audit;
import cn.yl.api.security.PermissionCode;
import cn.yl.api.security.RequiresPermission;
import cn.yl.api.security.SecurityUtils;
import cn.yl.common.api.R;
import cn.yl.common.idempotent.Idempotent;
import cn.yl.modules.archive.dto.DataRevealRequest;
import cn.yl.modules.archive.dto.DataRevealResponse;
import cn.yl.modules.archive.service.DataRevealService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 敏感字段明文查看接口（D-2）：{@code POST /api/v1/security/reveal}。
 *
 * <p><b>四道闸叠加</b>：
 *
 * <ul>
 *   <li>第二闸：{@link RequiresPermission}({@code data:reveal}，仅机构管理员持有)；
 *   <li>第四闸：{@code data:reveal} 属敏感点，{@code PermissionAspect} <b>自动</b>校验 {@code
 *       X-Second-Verify-Token} 一次性凭据（无需本类手写二次验证逻辑）；
 *   <li>第三闸：明文查询经 {@code ElderMapper} 发起，数据域按机构收口；
 *   <li>留痕：{@link Audit}({@code action=DATA_REVEAL, bizType=ELDER, sensitive=true})，方法成功返回后写 {@code
 *       audit_log(sensitive=1, second_verify=1)}。
 * </ul>
 *
 * <p>{@code Idempotency-Key} 必填（{@link Idempotent}({@code verifyBody=true})）。
 */
@RestController
@RequestMapping("/api/v1/security")
@RequiredArgsConstructor
public class DataRevealController {

    private final DataRevealService service;

    /** 查看敏感字段明文（二次验证 + 全留痕）。操作者一律取登录态。 */
    @PostMapping("/reveal")
    @RequiresPermission(PermissionCode.DATA_REVEAL)
    @Idempotent(verifyBody = true)
    @Audit(action = "DATA_REVEAL", bizType = "ELDER", sensitive = true)
    public R<DataRevealResponse> reveal(@Valid @RequestBody DataRevealRequest request) {
        return R.ok(service.reveal(request, SecurityUtils.requireUserId()));
    }
}
