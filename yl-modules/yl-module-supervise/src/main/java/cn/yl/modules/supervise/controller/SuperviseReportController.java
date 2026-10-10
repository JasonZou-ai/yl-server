package cn.yl.modules.supervise.controller;

import cn.yl.api.security.PermissionCode;
import cn.yl.api.security.RequiresPermission;
import cn.yl.api.security.SecurityUtils;
import cn.yl.common.api.R;
import cn.yl.common.idempotent.Idempotent;
import cn.yl.modules.supervise.dto.SuperviseReportView;
import cn.yl.modules.supervise.service.SuperviseReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B10 监管数据上报（{@code POST /api/v1/supervise/report}，支持重试 + 幂等）。
 *
 * <p>权限码 {@code supervise:report:submit}（2011 → ORG_ADMIN/SUPERVISOR）。{@link Idempotent}{@code
 * (verifyBody=true)} 要求 {@code Idempotency-Key} 请求头（缺 → 10007）。上报机构取自登录态，契约无 requestBody。
 *
 * <p>上报成功写 {@code retain_until}（只写值，归档排 P2-7 / D-5）。
 */
@RestController
@RequestMapping("/api/v1/supervise/report")
@RequiredArgsConstructor
public class SuperviseReportController {

    private final SuperviseReportService superviseReportService;

    /** 上报当前自然月监管报文（幂等：同月同机构重放不新增行）。 */
    @PostMapping
    @RequiresPermission(PermissionCode.SUPERVISE_REPORT_SUBMIT)
    @Idempotent(verifyBody = true)
    public R<SuperviseReportView> submit() {
        return R.ok(superviseReportService.submit(SecurityUtils.requireUser()));
    }
}
