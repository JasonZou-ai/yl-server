package cn.yl.modules.account.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 登录请求（MVP 账密渠道）。 */
@Data
public class LoginRequest {

    @NotBlank(message = "登录名不能为空")
    private String username;

    @NotBlank(message = "口令不能为空")
    private String password;
}
