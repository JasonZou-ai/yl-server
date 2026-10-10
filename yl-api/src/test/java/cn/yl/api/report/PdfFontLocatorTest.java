package cn.yl.api.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 报告 PDF 中文字体探针测试：候选优先级与<b>真实可渲染性</b>自检口径（P1-4a 部署前置项）。
 *
 * <p><b>2026-10-10 口径升级（用户核实指认假 ready）</b>：probe 不再以「文件存在」判定 ready——历史上容器装的 正是无法子集化的 Noto
 * CJK，文件在、探针说就绪、渲染实际豆腐块。本套用例的核心突变锚点： 若把 probe 退化回文件存在性判定，{@link
 * #unrenderableFontNeverReportedReady} 必须变红（red-line-guard-mutation-proof.md v1.2 第 5 盲区附则）。
 */
class PdfFontLocatorTest {

    @TempDir Path tempDir;

    /**
     * <b>核心突变锚点</b>：存在但不可加载的字体文件（垃圾字节冒充 .ttc）绝不能被探针报成 ready—— 旧实现「文件在即 ready」下，本用例因 {@code path ==
     * 垃圾文件} 而失败。
     *
     * <p>环境无关判定：无论机器上有没有系统字体，配置的垃圾文件都不许成为探针结论。
     */
    @Test
    void unrenderableFontNeverReportedReady() throws IOException {
        Path garbage = tempDir.resolve("fake-font.ttc");
        Files.write(garbage, new byte[] {0x00, 0x01, 0x00, 0x00});

        PdfFontLocator locator = new PdfFontLocator(garbage.toString());
        Map<String, Object> probe = locator.probe();

        assertFalse(
                garbage.toString().equals(probe.get("path")),
                "探针不得把「存在但不可加载」的文件报为结论（假 ready 复发即本用例变红）");
        if ("ready".equals(probe.get("status"))) {
            assertFalse(probe.get("path").equals(garbage.toString()), "ready 时必须落在真实可渲染的回退候选上");
        } else {
            assertEquals("missing", probe.get("status"), "无可渲染字体时必须报 missing");
        }
    }

    /**
     * 本机若有真实中文字体（Windows 优先 simsun.ttc——顺带覆盖 TTC 集合加载路径；CI 的 wqy-zenhei 同为 ttc）， 配置指向它时探针必须 ready
     * 且路径精确命中。TTC 加载逻辑退化（直接 {@code PDType0Font.load(doc, File)}）时 本用例变红（CI 38047512446 的真实事故形态）。
     */
    @Test
    void realFontReportedReadyWithExactPath() {
        String real =
                List.of(
                                "C:/Windows/Fonts/simsun.ttc",
                                "C:/Windows/Fonts/simhei.ttf",
                                "/usr/share/fonts/truetype/wqy/wqy-zenhei.ttc")
                        .stream()
                        .filter(p -> new File(p).isFile())
                        .findFirst()
                        .orElse(null);
        assumeTrue(real != null, "本机无已知真实中文字体，跳过（fontless 环境由 missing 用例覆盖）");

        PdfFontLocator locator = new PdfFontLocator(real);
        Map<String, Object> probe = locator.probe();

        assertEquals("ready", probe.get("status"), "真实字体必须被探针判为 ready");
        assertEquals(
                new File(real).getAbsolutePath(),
                probe.get("path"),
                "ready 路径必须精确命中配置字体（按 File 归一化比较）");
    }

    /** 不存在的配置路径永不入选：探针要么回退到系统真实字体、要么如实报 missing（而不是假装正常）。 */
    @Test
    void missingConfiguredFontNeverSelected() {
        String bogus = tempDir.resolve("no-such-font.ttf").toString();
        PdfFontLocator locator = new PdfFontLocator(bogus);

        Map<String, Object> probe = locator.probe();

        assertEquals(bogus, probe.get("configured"));
        assertFalse(bogus.equals(probe.get("path")), "不存在的配置路径不得成为结论");
        assertTrue(
                "ready".equals(probe.get("status")) || "missing".equals(probe.get("status")),
                "status 只允许 ready/missing 两态");
    }

    /** 自检结果进程内缓存（真实自检含字体解析与 PDF 保存，不宜挂在每次 /system/health 上）。 */
    @Test
    void probeResultIsCachedWithinProcess() {
        PdfFontLocator locator = new PdfFontLocator("");

        assertEquals(locator.probe(), locator.probe(), "同一实例重复探针必须复用缓存结果（返回防御性拷贝）");
    }

    /** 未配置时仍保留系统回退候选，避免把「没配」等同于「没字体」。 */
    @Test
    void blankConfigKeepsSystemFallbacks() {
        PdfFontLocator locator = new PdfFontLocator("  ");

        assertEquals("", locator.configuredPath(), "空白配置应归一化为空串");
        assertFalse(locator.candidates().isEmpty(), "必须保留系统回退候选");
    }
}
