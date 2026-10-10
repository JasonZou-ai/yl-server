package cn.yl.modules.account.dto;

import lombok.Data;

/** SMS-04 ticket 兑换登录态请求。 */
@Data
public class LoginByTicketRequest {

    /** SMS-03 返回的一次性凭据。 */
    private String ticket;

    /** 端侧场景（用于 device_type 映射）；可缺省，缺省时取自 ticket 内场景。 */
    private String scene;
}
