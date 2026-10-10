package cn.yl.modules.report.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * CSV 渲染器（B9，§2.4 口径）。
 *
 * <table>
 *   <caption>渲染口径</caption>
 *   <tr><td>编码</td><td>UTF-8，文件首字节 = BOM {@code 0xEF 0xBB 0xBF}</td></tr>
 *   <tr><td>行分隔</td><td>CRLF（{@code \r\n}）</td></tr>
 *   <tr><td>字段分隔</td><td>{@code ,}</td></tr>
 *   <tr><td>引号包裹</td><td>字段含 {@code , " \n \r} 时用 {@code "} 包裹，内部 {@code "} → {@code ""}</td></tr>
 *   <tr><td>公式注入转义</td><td>单元格首字符 ∈ {@code = + - @ \t \r} 时前置 {@code '}</td></tr>
 * </table>
 */
@Component
public class ExportCsvWriter {

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private static final String CRLF = "\r\n";

    /** 公式注入危险首字符集（Excel/LibreOffice 公式起始符）。 */
    private static final String FORMULA_TRIGGERS = "=+-@\t\r";

    /** 首行固定表头（导出类型 EVAL_LIST 的列名集合）。 */
    private static final String[] HEADER = {
        "评估单号", "能力等级", "老人姓名", "身份证号", "手机号", "评估状态", "所属机构", "出具时间"
    };

    /**
     * 渲染 CSV 字节（含 UTF-8 BOM + 固定表头 + CRLF）。
     *
     * @param rows 已按展示口径成形的行（每行单元格数与表头一致）
     * @return CSV 字节（首 3 字节为 BOM）
     */
    public byte[] writeCsv(List<String[]> rows) {
        StringBuilder sb = new StringBuilder();
        appendLine(sb, HEADER);
        if (rows != null) {
            for (String[] row : rows) {
                appendLine(sb, row);
            }
        }
        byte[] body = sb.toString().getBytes(StandardCharsets.UTF_8);
        byte[] out = new byte[UTF8_BOM.length + body.length];
        System.arraycopy(UTF8_BOM, 0, out, 0, UTF8_BOM.length);
        System.arraycopy(body, 0, out, UTF8_BOM.length, body.length);
        return out;
    }

    private void appendLine(StringBuilder sb, String[] cells) {
        for (int i = 0; i < cells.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(escapeCell(cells[i]));
        }
        sb.append(CRLF);
    }

    /**
     * 单元格转义（唯一约定，勿各写一份）。
     *
     * <p>步骤：① 首字符 ∈ {@code = + - @ \t \r} → 前置 {@code '}；② 含 {@code , " \n \r} → {@code "} 包裹且内部
     * {@code "} → {@code ""}。
     *
     * @param cell 原始单元格（null 视作空串）
     * @return 转义后字符串
     */
    public static String escapeCell(String cell) {
        String value = cell == null ? "" : cell;
        if (!value.isEmpty() && FORMULA_TRIGGERS.indexOf(value.charAt(0)) >= 0) {
            value = "'" + value;
        }
        if (needsQuoting(value)) {
            value = '"' + value.replace("\"", "\"\"") + '"';
        }
        return value;
    }

    private static boolean needsQuoting(String value) {
        return value.indexOf(',') >= 0
                || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;
    }
}
