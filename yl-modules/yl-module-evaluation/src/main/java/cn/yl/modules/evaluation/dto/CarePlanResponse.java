package cn.yl.modules.evaluation.dto;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.Data;

/**
 * 照护方案响应（PRD P02「照护建议」）。
 *
 * <p>{@code status} 0-待确认 / 1-已确认：评估员建议后需管理员确认才对家属可见，避免单人闭环。
 */
@Data
public class CarePlanResponse {

    private Long planId;
    private Long reportId;
    private Long elderId;
    private String gradeCode;

    /** 0-待确认 1-已确认。 */
    private Integer status;

    private Long suggestBy;
    private Long confirmedBy;
    private LocalDateTime confirmedAt;
    private LocalDateTime createdAt;

    private List<CarePlanItem> items = new ArrayList<>();

    public List<CarePlanItem> getItems() {
        return List.copyOf(items);
    }

    public void setItems(List<CarePlanItem> items) {
        this.items = items == null ? new ArrayList<>() : new ArrayList<>(items);
    }
}
