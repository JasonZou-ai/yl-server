package cn.yl.api.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 报告 PDF 中文字体定位器测试：候选优先级与健康自检口径（P1-4a 部署前置项）。 */
class PdfFontLocatorTest {

    @TempDir Path tempDir;

    /** 显式配置的字体路径优先级最高：存在即被选中，且探针报 ready。 */
    @Test
    void configuredPathTakesPrecedenceAndReportsReady() throws IOException {
        Path font = tempDir.resolve("NotoSansCJK-Regular.ttc");
        Files.write(font, new byte[] {0x00, 0x01, 0x00, 0x00});

        PdfFontLocator locator = new PdfFontLocator(font.toString());

        assertEquals(font.toString(), locator.candidates().get(0), "配置路径必须是第一个候选");
        assertTrue(locator.locate().isPresent(), "存在的字体文件应被定位");
        Map<String, Object> probe = locator.probe();
        assertEquals("ready", probe.get("status"));
        assertEquals(font.toString(), probe.get("path"));
    }

    /**
     * 配置了不存在的路径时：绝不选中该路径；系统若无任何候选命中，则探针必须报 missing（而不是假装正常）。
     *
     * <p>本用例不假设运行环境是否装了中文字体（Windows 开发机通常有、精简容器通常无）， 只固定「不存在的配置路径永不被选中」与「status 与 locate()
     * 一致」两条不变量。
     */
    @Test
    void missingConfiguredFontNeverSelectedAndProbeAgreesWithLocate() {
        String bogus = tempDir.resolve("no-such-font.ttf").toString();
        PdfFontLocator locator = new PdfFontLocator(bogus);

        Map<String, Object> probe = locator.probe();

        assertEquals(bogus, probe.get("configured"));
        if (locator.locate().isEmpty()) {
            assertEquals("missing", probe.get("status"), "无可用字体时必须报 missing");
            assertEquals("", probe.get("path"));
        } else {
            assertEquals("ready", probe.get("status"), "已定位到字体时必须报 ready");
            assertFalse(bogus.equals(probe.get("path")), "不存在的配置路径不得被选中，须回退到真实存在的系统候选");
        }
    }

    /** 未配置时仍保留系统回退候选，避免把"没配"等同于"没字体"。 */
    @Test
    void blankConfigKeepsSystemFallbacks() {
        PdfFontLocator locator = new PdfFontLocator("  ");

        assertEquals("", locator.configuredPath(), "空白配置应归一化为空串");
        assertFalse(locator.candidates().isEmpty(), "必须保留系统回退候选");
    }
}
