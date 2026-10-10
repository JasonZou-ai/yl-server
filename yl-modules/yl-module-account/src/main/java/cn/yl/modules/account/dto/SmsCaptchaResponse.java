package cn.yl.modules.account.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** SMS-01 图形验证码响应。 */
@Data
@AllArgsConstructor
public class SmsCaptchaResponse {

    private String captchaId;

    /** PNG data URI（{@code data:image/png;base64,…}）。 */
    private String captchaImage;

    /** 有效期（秒）。 */
    private long ttl;
}
