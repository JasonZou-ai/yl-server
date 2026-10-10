package cn.yl.modules.account.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** SMS-02 发送响应（★ 不含任何「是否已注册」信息，防枚举）。 */
@Data
@AllArgsConstructor
public class SmsSendResponse {

    /** 脱敏手机号，如 138****8000。 */
    private String maskedPhone;

    /** 验证码有效期（秒）。 */
    private long ttl;
}
