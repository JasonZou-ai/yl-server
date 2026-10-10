package cn.yl.modules.archive.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import lombok.Data;

/**
 * 敏感字段明文查看请求（{@code POST /api/v1/security/reveal}）。
 *
 * <p>{@code bizType} 本期仅实现 {@code ELDER}；{@code USER} 属账号域，收到即返回 {@link
 * cn.yl.common.exception.ErrorCode#PARAM_INVALID}(10001)「暂不支持」（登记为残留，不加新权限码）。
 *
 * <p>{@code fields} 为字段名白名单入参：仅允许 {@code name/idCardNo/phone/address}，其余字段名 → 10001。
 */
@Data
public class DataRevealRequest {

    /** 业务类型：本期仅 {@code ELDER}。 */
    @NotBlank(message = "bizType 不能为空")
    private String bizType;

    /** 业务对象 id（对 {@code ELDER} 即 {@code elder.id}）。 */
    @NotNull(message = "bizId 不能为空")
    private Long bizId;

    /** 待查看明文的字段名列表（白名单）。 */
    @NotEmpty(message = "fields 不能为空")
    private List<String> fields = List.of();

    /** 字段名列表（不可变视图）；防御性拷贝避免暴露内部可变表示（EI_EXPOSE_REP）。 */
    public List<String> getFields() {
        return List.copyOf(fields);
    }

    public void setFields(List<String> fields) {
        this.fields = fields == null ? List.of() : List.copyOf(fields);
    }
}
