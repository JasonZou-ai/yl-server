package cn.yl.modules.account.dto;

import lombok.Data;

/** SMS-03 校验短信验证码请求。 */
@Data
public class SmsVerifyRequest {

    private String phone;

    /** 用途：LOGIN / BIND / CHANGE_PHONE。 */
    private String purpose;

    /** 短信验证码。 */
    private String code;

    /** 端侧场景：WECHAT / DOUYIN / IOS / ANDROID。 */
    private String scene;
}
