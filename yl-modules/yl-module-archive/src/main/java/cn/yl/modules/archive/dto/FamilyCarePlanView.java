package cn.yl.modules.archive.dto;

import java.time.LocalDateTime;
import java.util.List;
import lombok.Data;

/**
 * 照护方案视图（D-4 家属端 {@code GET /family/bindings/{relationId}/care-plan}）。
 *
 * <p>无已确认方案时返回<b>空方案</b>（{@code planId=null}，{@code items=[]}，HTTP 200 + {@code code=0}），<b>不返
 * 10004</b>（"尚未生成"非越权，走前端空态）。
 *
 * <p>{@code items} 的访问器做不可变快照（同 {@code LoginResponse.roles} 范式），避免内部列表被外部改写（SpotBugs
 * EI_EXPOSE_REP/REP2）。
 */
@Data
public class FamilyCarePlanView {

    private Long planId;

    private Long reportId;

    private Long elderId;

    private String gradeCode;

    private LocalDateTime confirmedAt;

    /** 照护条目（空态为空列表）。 */
    private List<String> items;

    /** 读取返回不可变视图（未赋值时为空集合）。 */
    public List<String> getItems() {
        return items == null ? List.of() : List.copyOf(items);
    }

    /** 写入时快照。 */
    public void setItems(List<String> items) {
        this.items = items == null ? List.of() : List.copyOf(items);
    }
}
