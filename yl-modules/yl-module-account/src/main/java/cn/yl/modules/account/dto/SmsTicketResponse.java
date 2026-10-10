package cn.yl.modules.account.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

/** SMS-03 校验响应：一次性 ticket。 */
@Data
@AllArgsConstructor
public class SmsTicketResponse {

    private String ticket;

    /** ticket 有效期（秒）。 */
    private long ticketTtl;
}
