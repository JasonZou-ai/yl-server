package cn.yl.modules.report.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.Data;

/**
 * B9 创建导出任务入参（{@code POST /api/v1/supervise/exports}）。
 *
 * <p>{@code queryParams} 为入参快照，仅在本次调用内被序列化为 JSON 落 {@code export_task.query_params}。为不暴露内部可变映射， 其
 * getter/setter 做<b>防御性拷贝</b>（对齐 {@code DataRevealResponse} 范式，避免 SpotBugs {@code
 * EI_EXPOSE_REP/REP2}）。
 */
@Data
public class ExportCreateRequest {

    /** 导出类型：D-3 仅支持 EVAL_LIST（契约 enum [EVAL_LIST, REPORT, STAT]）。 */
    @NotBlank private String exportType;

    /** 导出格式：D-3 仅支持 CSV（裁决 2，零新依赖）。 */
    @NotBlank private String format;

    /** 查询条件快照（可选）。 */
    private Map<String, Object> queryParams;

    /** 防御性拷贝返回（不可变视图，外部无法改写内部映射）。 */
    public Map<String, Object> getQueryParams() {
        return queryParams == null
                ? null
                : Collections.unmodifiableMap(new LinkedHashMap<>(queryParams));
    }

    /** 防御性拷贝存入（外部持有入参映射的变更不再影响本对象）。 */
    public void setQueryParams(Map<String, Object> queryParams) {
        this.queryParams = queryParams == null ? null : new LinkedHashMap<>(queryParams);
    }
}
