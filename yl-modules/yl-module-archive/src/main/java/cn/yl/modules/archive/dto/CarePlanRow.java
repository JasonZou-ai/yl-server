package cn.yl.modules.archive.dto;

import java.time.LocalDateTime;
import lombok.Data;

/** {@code care_plan} 只读投影行（D-4）。 */
@Data
public class CarePlanRow {

    private Long id;

    private Long reportId;

    private Long elderId;

    private String gradeCode;

    /** 照护条目（JSON 串；服务层用 Jackson 解为字符串列表）。 */
    private String itemsJson;

    private Long confirmedBy;

    private LocalDateTime confirmedAt;

    /** 0-待确认 1-已确认。 */
    private Integer status;
}
