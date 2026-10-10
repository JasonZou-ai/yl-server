package cn.yl.modules.archive.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

/** 老人侧拒绝绑定请求（D-4，{@code POST /api/v1/family/bindings/{id}/reject}）。 */
@Data
public class FamilyBindingRejectRequest {

    /** 拒绝原因（可选）。 */
    @Size(max = 255, message = "拒绝原因长度不得超过 255")
    private String reason;
}
