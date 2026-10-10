package cn.yl.api.report;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 报告 PDF 中文字体定位器（部署前置项：候选真源 + 启动/健康自检）。
 *
 * <p>为什么必须显式配置：PDFBox 内置字体（Helvetica/Courier 等）<b>不含 CJK 字形</b>，字体缺失时渲染不会失败， 只是中文被降级为占位文本并置 {@code
 * chineseEmbedded=false}——属"静默不可用"，线上才暴露。 故容器镜像内置 Noto CJK 并以 {@code REPORT_PDF_FONT}
 * 显式指向，同时由本类统一候选口径。
 *
 * <p>本类放在 {@code yl-api}（共享层）而非 evaluation 模块，原因是健康探针（{@code HealthController}）位于 yl-api， 而模块依赖方向为
 * {@code modules → api}，反向引用会成环；候选列表因此收敛到此处作<b>唯一真源</b>， 由 {@code ReportPdfService} 与健康探针共用。
 */
@Component
public class PdfFontLocator {

    /** 系统常见中文字体回退候选（配置项 {@code yl.report.pdf.font-path} 优先级最高）。 */
    private static final List<String> SYSTEM_CANDIDATES =
            List.of(
                    "C:/Windows/Fonts/simhei.ttf",
                    "C:/Windows/Fonts/simsun.ttc",
                    "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
                    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc");

    private final String configuredPath;

    public PdfFontLocator(@Value("${yl.report.pdf.font-path:}") String configuredPath) {
        this.configuredPath = configuredPath == null ? "" : configuredPath.trim();
    }

    /** 全部候选路径（配置优先 + 系统回退），顺序即探测顺序。 */
    public List<String> candidates() {
        List<String> all = new ArrayList<>();
        if (!configuredPath.isEmpty()) {
            all.add(configuredPath);
        }
        all.addAll(SYSTEM_CANDIDATES);
        return List.copyOf(all);
    }

    /** 首个真实存在的中文字体文件；均不可用返回空（调用方应降级渲染而非失败）。 */
    public Optional<File> locate() {
        for (String candidate : candidates()) {
            File file = new File(candidate);
            if (file.isFile()) {
                return Optional.of(file);
            }
        }
        return Optional.empty();
    }

    /** 配置项原值（未配置为空串）。 */
    public String configuredPath() {
        return configuredPath;
    }

    /**
     * 健康探针口径（供 {@code GET /api/v1/system/health} 的 {@code reportFont} 字段使用）： {@code status=ready}
     * 表示已定位到可用中文字体，{@code missing} 表示线上 PDF 中文将降级。
     */
    public Map<String, Object> probe() {
        Optional<File> found = locate();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", found.isPresent() ? "ready" : "missing");
        result.put("path", found.map(File::getAbsolutePath).orElse(""));
        result.put("configured", configuredPath);
        return result;
    }
}
