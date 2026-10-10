package cn.yl.modules.evaluation.dto;

import lombok.Data;

/**
 * 报告 PDF 生成结果。
 *
 * <p>{@code pdfKey} 为对象键（MVP 本地存储，后续接 COS 时键名不变）； {@code qrContent} 为二维码验真内容（PRD P02 验真区）。
 */
@Data
public class ReportPdfResponse {

    private Long reportId;
    private String reportNo;
    private String pdfKey;
    private String qrContent;

    /** PDF 字节数。 */
    private long sizeBytes;

    /** 中文是否成功嵌入字体（false 表示降级渲染，中文可能缺字形）。 */
    private boolean chineseEmbedded;
}
