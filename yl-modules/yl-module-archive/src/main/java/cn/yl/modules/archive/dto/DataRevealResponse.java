package cn.yl.modules.archive.dto;

import java.util.Map;
import lombok.Getter;

/**
 * 敏感字段明文查看响应（字段名 → 明文映射）。
 *
 * <p>不可变值对象：{@code fields} 在构造时做不可变拷贝，避免调用方通过返回值改写内部映射。
 */
@Getter
public class DataRevealResponse {

    private final String bizType;
    private final Long bizId;
    private final Map<String, String> fields;

    public DataRevealResponse(String bizType, Long bizId, Map<String, String> fields) {
        this.bizType = bizType;
        this.bizId = bizId;
        this.fields = fields == null ? Map.of() : Map.copyOf(fields);
    }
}
