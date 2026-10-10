package cn.yl.modules.report.dto;

import java.time.LocalDateTime;
import lombok.Data;

/**
 * 导出投影行（CSV 数据源，B9）。
 *
 * <p>{@code elderNameEnc} / {@code phoneEnc} 为库内<b>密文</b>列；服务层在渲染前经 {@code SensitiveFieldCodec}
 * 解密并脱敏为 展示值（编解码器未装配时以 {@code ***} 兜底，绝不明文/原样输出密文）。
 */
@Data
public class ExportSourceRow {

    private String orderNo;
    private String gradeCode;
    private String elderNameEnc;
    private String idCardMask;
    private String phoneEnc;
    private String status;
    private Long orgId;
    private LocalDateTime publishedAt;
}
