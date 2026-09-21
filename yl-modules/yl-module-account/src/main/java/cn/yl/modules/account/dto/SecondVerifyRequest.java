package cn.yl.modules.account.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 二次验证请求（SCAN/PHONE/FACE）。 */
@Data
public class SecondVerifyRequest {

    @NotBlank(message = "验证方式不能为空")
    private String method;

    @NotBlank(message = "验证凭据不能为空")
    private String credential;
}
