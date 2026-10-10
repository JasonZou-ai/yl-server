package cn.yl.api.report;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.fontbox.ttf.TrueTypeFont;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 报告 PDF 中文字体定位器（部署前置项：候选真源 + 真实可渲染性自检）。
 *
 * <p>为什么必须显式配置：PDFBox 内置字体（Helvetica/Courier 等）<b>不含 CJK 字形</b>，字体缺失时渲染不会失败， 只是中文被降级为占位文本并置 {@code
 * chineseEmbedded=false}——属"静默不可用"，线上才暴露。 故容器镜像内置 wqy-zenhei 并以 {@code REPORT_PDF_FONT}
 * 显式指向，同时由本类统一候选口径。
 *
 * <p>本类放在 {@code yl-api}（共享层）而非 evaluation 模块，原因是健康探针（{@code HealthController}）位于 yl-api， 而模块依赖方向为
 * {@code modules → api}，反向引用会成环；候选列表与加载逻辑因此收敛到此处作<b>唯一真源</b>， 由 {@code ReportPdfService} 与健康探针共用。
 */
@Component
public class PdfFontLocator {

    private static final Logger LOGGER = LoggerFactory.getLogger(PdfFontLocator.class);

    /** 自检渲染用文本（须含真实汉字，空串或纯 ASCII 起不到字形验证作用）。 */
    private static final String PROBE_TEXT = "银龄守护";

    private static final float PROBE_FONT_SIZE = 12f;

    /**
     * 系统常见中文字体回退候选（配置项 {@code yl.report.pdf.font-path} 优先级最高）。
     *
     * <p><b>Linux 侧首选 wqy-zenhei（2026-10-10）</b>：PDFBox 的 PDType0Font 子集化只支持 TrueType(glyf)
     * outline；Noto CJK 是 CFF(OTF) outline（「OTF fonts do not have a glyf table」），加载后渲染仍失败 （CI
     * 38048586455 实证）。文泉驿正黑同为 ttc 容器但内部是 TrueType outline，与 simsun 同构可用； noto
     * 候选保留在后仅为兼容已装环境（加载失败会自动落到下一候选）。
     */
    private static final List<String> SYSTEM_CANDIDATES =
            List.of(
                    "C:/Windows/Fonts/simhei.ttf",
                    "C:/Windows/Fonts/simsun.ttc",
                    "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc",
                    "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc",
                    "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc");

    private final String configuredPath;

    /**
     * 自检结果缓存。字体文件属部署期固定产物（进程运行中不会被替换为不同内容），真实自检涉及 字体解析与 PDF 保存，不宜挂在每次 {@code /system/health}
     * 上反复执行；装/换字体后重启即刷新。
     */
    private volatile Map<String, Object> cachedProbe;

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

    /** 首个真实存在的中文字体文件（仅文件存在性，不代表可加载）；均不存在返回空。 */
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
     * 依候选顺序做<b>真实加载</b>，返回首个成功嵌入 {@code doc} 的字体及其文件路径；全部失败返回空（调用方应降级渲染而非失败）。
     *
     * <p>TTC 集合不能经 {@code PDType0Font.load(doc, File)} 直接读取（「'head' table is mandatory」，CI
     * 38047512446 实证），必须经 {@link TrueTypeCollection} 取子字体；集合引用由 {@code openedCollections}
     * 带出，<b>必须活到 {@code doc.save()} 之后</b>（子集化在 save 阶段读取字体数据），由调用方统一关闭。
     */
    public Optional<PDFont> loadFirst(PDDocument doc, List<TrueTypeCollection> openedCollections) {
        for (String candidate : candidates()) {
            File file = new File(candidate);
            if (!file.isFile()) {
                continue;
            }
            Optional<PDFont> loaded = open(doc, file, openedCollections);
            if (loaded.isPresent()) {
                return loaded;
            }
        }
        LOGGER.warn("未找到可用的中文字体，报告 PDF 将降级渲染（中文缺字形）。请配置 yl.report.pdf.font-path");
        return Optional.empty();
    }

    private Optional<PDFont> open(
            PDDocument doc, File file, List<TrueTypeCollection> openedCollections) {
        try {
            PDFont font;
            if (file.getName().toLowerCase(Locale.ROOT).endsWith(".ttc")) {
                TrueTypeCollection collection = new TrueTypeCollection(file);
                openedCollections.add(collection);
                List<TrueTypeFont> fonts = new ArrayList<>(1);
                collection.processAllFonts(fonts::add);
                if (fonts.isEmpty()) {
                    LOGGER.warn("TTC 集合不含任何子字体，尝试下一个候选: {}", file.getAbsolutePath());
                    return Optional.empty();
                }
                font = PDType0Font.load(doc, fonts.get(0), true);
            } else {
                // 两参 File 变体默认即嵌入+子集化；三参 boolean 只有 InputStream/TrueTypeFont 重载
                font = PDType0Font.load(doc, file);
            }
            return Optional.of(font);
        } catch (IOException e) {
            LOGGER.warn("加载 PDF 中文字体失败，尝试下一个候选: {} ({})", file.getAbsolutePath(), e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 健康探针口径（供 {@code GET /api/v1/system/health} 的 {@code reportFont} 字段使用）： {@code status=ready}
     * 表示已找到<b>真实可渲染</b>（加载 → 绘制汉字 → save 全链通过）的中文字体， {@code missing} 表示线上 PDF 中文将降级。
     *
     * <p><b>为什么不是文件存在性判定</b>：历史上 {@code locate()} 找到文件即报 ready，而容器里装的正是 无法子集化的 Noto
     * CJK——「文件在、探针说就绪、渲染实际豆腐块」，整个静默降级期间健康检查一直在说谎 （2026-10-10
     * 用户核实指认）。本探针因此做全保真自检：任一步失败即视为该候选不可用，换下一候选。
     */
    public Map<String, Object> probe() {
        Map<String, Object> snapshot = cachedProbe;
        if (snapshot == null) {
            synchronized (this) {
                if (cachedProbe == null) {
                    cachedProbe = doProbe();
                }
                snapshot = cachedProbe;
            }
        }
        // 防御性拷贝：调用方（健康响应序列化等）改动不得污染缓存本体
        return new LinkedHashMap<>(snapshot);
    }

    private Map<String, Object> doProbe() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", configuredPath);
        for (String candidate : candidates()) {
            File file = new File(candidate);
            if (!file.isFile() || !renderable(file)) {
                continue;
            }
            result.put("status", "ready");
            result.put("path", file.getAbsolutePath());
            return result;
        }
        result.put("status", "missing");
        result.put("path", "");
        return result;
    }

    /** 「文件在」≠「能用」：真实走一遍 加载 → 绘制汉字 → save（子集化发生在 save 阶段），任一步失败即不可用。 */
    private boolean renderable(File file) {
        List<TrueTypeCollection> opened = new ArrayList<>();
        try (PDDocument doc = new PDDocument()) {
            Optional<PDFont> loaded = open(doc, file, opened);
            if (loaded.isEmpty()) {
                return false;
            }
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(doc, page)) {
                content.beginText();
                content.setFont(loaded.get(), PROBE_FONT_SIZE);
                content.newLineAtOffset(36, 720);
                content.showText(PROBE_TEXT);
                content.endText();
            }
            doc.save(new ByteArrayOutputStream());
            return true;
        } catch (Exception e) {
            // IOException 与 OTF 子集化的 UnsupportedOperationException（「OTF fonts do not have a
            // glyf table」）都必须按「不可用」处理，这也是 Noto CJK 曾长期假 ready 的直接原因
            LOGGER.warn("PDF 字体自检未通过，尝试下一候选: {} ({})", file.getAbsolutePath(), e.getMessage());
            return false;
        } finally {
            closeAll(opened);
        }
    }

    private void closeAll(List<TrueTypeCollection> opened) {
        for (TrueTypeCollection collection : opened) {
            try {
                collection.close();
            } catch (IOException e) {
                LOGGER.warn("关闭 TTC 集合失败（不影响探测结论）: {}", e.getMessage());
            }
        }
    }
}
