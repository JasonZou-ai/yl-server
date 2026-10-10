package cn.yl.modules.supervise.dto;

import java.time.LocalDateTime;
import lombok.Data;

/** B10 监管上报视图。 */
@Data
public class SuperviseReportView {

    private Long id;
    private String reportMonth;
    private Long orgId;
    private Integer status;
    private LocalDateTime sentAt;
}
