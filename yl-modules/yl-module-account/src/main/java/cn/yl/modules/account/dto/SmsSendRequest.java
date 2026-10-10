package cn.yl.modules.account.dto;

import lombok.Data;

/** SMS-02 发送短信验证码请求。 */
@Data
public class SmsSendRequest {

    private String phone;

    /** 用途：LOGIN / BIND / CHANGE_PHONE。 */
    private String purpose;

    /** 端侧场景：WECHAT / DOUYIN / IOS / ANDROID。 */
    private String scene;

    /** 触发图形验证时必填。 */
    private String captchaId;

    /** 用户填写的图形验证码。 */
    private String captchaCode;
}
