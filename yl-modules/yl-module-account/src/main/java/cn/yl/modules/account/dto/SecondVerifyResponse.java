package cn.yl.modules.account.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** 二次验证响应：一次性凭据（携带于后续敏感请求的 X-Second-Verify-Token 头）。 */
@Data
@AllArgsConstructor
public class SecondVerifyResponse {

    private String token;

    /** 凭据有效期（秒）。 */
    private long expiresIn;
}
