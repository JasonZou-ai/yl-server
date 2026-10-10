package cn.yl.modules.report.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/** CSV 渲染器测试：BOM 魔数 / 固定表头 / CRLF / 公式注入转义（含注入样本）。 */
class ExportCsvWriterTest {

    private final ExportCsvWriter writer = new ExportCsvWriter();

    @Test
    void writeCsvStartsWithUtf8Bom() {
        byte[] csv = writer.writeCsv(List.of());

        assertEquals((byte) 0xEF, csv[0]);
        assertEquals((byte) 0xBB, csv[1]);
        assertEquals((byte) 0xBF, csv[2]);
    }

    @Test
    void writeCsvHasFixedHeaderAndCrlf() {
        byte[] csv = writer.writeCsv(List.of());
        String body = new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);

        assertTrue(body.startsWith("评估单号,能力等级,老人姓名,身份证号,手机号,评估状态,所属机构,出具时间"), body);
        assertTrue(body.endsWith("\r\n"), "行分隔必须为 CRLF");
    }

    @Test
    void escapesFormulaInjectionPrefixes() {
        assertEquals("'=SUM(A1)", ExportCsvWriter.escapeCell("=SUM(A1)"));
        assertEquals("'+1", ExportCsvWriter.escapeCell("+1"));
        assertEquals("'-1", ExportCsvWriter.escapeCell("-1"));
        assertEquals("'@import", ExportCsvWriter.escapeCell("@import"));
        assertEquals("'\tfoo", ExportCsvWriter.escapeCell("\tfoo"));
        // \r 同时触发"公式转义"与"引号包裹"，转义前缀落在引号内
        assertTrue(ExportCsvWriter.escapeCell("\rfoo").contains("'\rfoo"));
    }

    @Test
    void escapesQuotingWhenNeeded() {
        assertEquals("\"a,b\"", ExportCsvWriter.escapeCell("a,b"));
        assertEquals("\"he\"\"llo\"", ExportCsvWriter.escapeCell("he\"llo"));
    }

    @Test
    void leavesPlainCellUnchanged() {
        assertEquals("RP-ABC123", ExportCsvWriter.escapeCell("RP-ABC123"));
        assertEquals("", ExportCsvWriter.escapeCell(null));
    }

    /** 注入样本：以 {@code =} 开头的单元格，渲染后必须以 {@code '} 开头（防公式注入）。 */
    @Test
    void injectionSampleCellIsNeutralizedInOutput() {
        byte[] csv = writer.writeCsv(List.<String[]>of(new String[] {"=cmd|'/C calc'!A1", "L1"}));
        String body = new String(csv, 3, csv.length - 3, StandardCharsets.UTF_8);

        assertTrue(body.contains("'=cmd|'/C calc'!A1"), body);
    }
}
