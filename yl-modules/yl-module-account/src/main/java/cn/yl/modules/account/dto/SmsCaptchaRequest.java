package cn.yl.modules.account.dto;

import lombok.Data;

/** SMS-01 图形验证码请求。 */
@Data
public class SmsCaptchaRequest {

    /** 图形验证码用途场景标签（缺省 sms_send）；仅作文档/日志，不参与键构造。 */
    private String scene;
}
