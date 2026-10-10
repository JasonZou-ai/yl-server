package cn.yl.modules.report.controller;

import cn.yl.api.security.Audit;
import cn.yl.api.security.PermissionCode;
import cn.yl.api.security.RequiresPermission;
import cn.yl.api.security.SecurityUtils;
import cn.yl.common.api.R;
import cn.yl.common.idempotent.Idempotent;
import cn.yl.modules.report.dto.ExportCreateRequest;
import cn.yl.modules.report.dto.ExportTaskView;
import cn.yl.modules.report.service.SuperviseExportService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * B9 创建导出任务（{@code POST /api/v1/supervise/exports}）。
 *
 * <p>三类注解联动：{@link RequiresPermission}{@code (data:export)}（第二闸，敏感点 → 第四闸二次验证）+ {@link
 * Idempotent}{@code (verifyBody=true)}（{@code Idempotency-Key} 必填，缺 → 10007）+ {@link Audit}{@code
 * (sensitive=true)}（成功后写 {@code audit_log(sensitive=1)}）。
 *
 * <p>发起人/机构取自登录态（{@link SecurityUtils#requireUser()}），请求体不得承载身份。
 */
@RestController
@RequestMapping("/api/v1/supervise/exports")
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification =
                                "SuperviseExportService 是 Spring 容器管理的无状态单例 Bean，构造器注入是标准 DI 用法"))
public class SuperviseExportController {

    private final SuperviseExportService superviseExportService;

    /** 创建导出任务（≤5000 同步 CSV，全留痕）。 */
    @PostMapping
    @RequiresPermission(PermissionCode.DATA_EXPORT)
    @Idempotent(verifyBody = true)
    @Audit(action = "EXPORT", bizType = "EXPORT_TASK", sensitive = true)
    public R<ExportTaskView> create(@Valid @RequestBody ExportCreateRequest request) {
        return R.ok(superviseExportService.create(request, SecurityUtils.requireUser()));
    }
}
