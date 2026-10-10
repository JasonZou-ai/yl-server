package cn.yl.api.health;

import cn.yl.api.report.PdfFontLocator;
import cn.yl.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康探针：用于本地一键启动自检与部署流水线存活探测。路径规范化为 /api/v1/system/health，与 OpenAPI 契约、安全放行清单保持一致。
 *
 * <p>除存活状态外，额外暴露 <b>报告 PDF 中文字体自检</b>（{@code reportFont}）：字体缺失不会让服务起不来，只会让线上报告中文 静默降级为占位文本（见 {@link
 * PdfFontLocator}），属"部署了但不可用"，必须在部署验收时一眼可见。
 */
@Tag(name = "健康检查")
@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class HealthController {

    private final PdfFontLocator fontLocator;

    @Operation(summary = "服务健康检查（含报告 PDF 中文字体自检）")
    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "yl-server");
        body.put("standard", "GB/T 42195-2022");
        body.put("time", OffsetDateTime.now().toString());
        body.put("reportFont", fontLocator.probe());
        return R.ok(body);
    }
}
