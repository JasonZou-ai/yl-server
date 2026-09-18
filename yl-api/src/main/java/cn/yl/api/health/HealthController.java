package cn.yl.api.health;

import cn.yl.common.api.R;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 健康探针：用于本地一键启动自检与部署流水线存活探测。路径规范化为 /api/v1/system/health，与 OpenAPI 契约、安全放行清单保持一致。 */
@Tag(name = "健康检查")
@RestController
@RequestMapping("/api/v1/system")
public class HealthController {

    @Operation(summary = "服务健康检查")
    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("service", "yl-server");
        body.put("standard", "GB/T 42195-2022");
        body.put("time", OffsetDateTime.now().toString());
        return R.ok(body);
    }
}
