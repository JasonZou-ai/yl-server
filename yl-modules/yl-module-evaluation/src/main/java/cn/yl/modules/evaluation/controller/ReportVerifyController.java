package cn.yl.modules.evaluation.controller;

import cn.yl.common.api.R;
import cn.yl.modules.evaluation.dto.ReportVerifyResponse;
import cn.yl.modules.evaluation.service.ReportVerifyService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * B8 二维码验真（{@code GET /api/v1/reports/verify}，<b>public 端点</b>）。
 *
 * <p><b>无 {@code @RequiresPermission}</b>：扫码即验、无登录态。该路径已在 {@code SecurityConfig.filterChain} 的
 * {@code permitAll} 列表（零改动）。本 handler 进 {@code ControllerAuthorizationCoverageTest} 白名单第 5 条（公开验真）。
 *
 * <p>安全边界 = 「最小返回面」：只返回 {@code {valid, gradeCode, publishedAt}}，绝不返回姓名/身份证/手机号（裁决 4）。
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
public class ReportVerifyController {

    private final ReportVerifyService reportVerifyService;

    /** 扫码验真报告有效性（公开端点，无权限码，由最小返回面保证安全）。 */
    @GetMapping("/verify")
    public R<ReportVerifyResponse> verify(@RequestParam("code") String code) {
        return R.ok(reportVerifyService.verify(code));
    }
}
