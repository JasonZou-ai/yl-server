package cn.yl.modules.account.dto;

import lombok.Data;

/**
 * 登录请求（四端统一，B2 / rpW1xZ）。
 *
 * <p>字段按渠道选用，**必填校验下沉到各渠道适配器**（账密渠道校验 username/password，平台授权渠道校验 code）， 因为不同渠道的必填项本就不同，在 DTO 上写死
 * {@code @NotBlank} 会让第三方渠道永远无法通过 Bean Validation。
 *
 * <p>{@code code} / {@code phoneCode} 均为平台一次性凭据，用过即废，**不落库、不入日志**。
 */
@Data
public class LoginRequest {

    /** 登录名（IOS/ANDROID 账密渠道必填）。 */
    private String username;

    /** 口令（IOS/ANDROID 账密渠道必填；服务端仅比对 BCrypt 散列，不落明文）。 */
    private String password;

    /** 平台登录码（WECHAT/DOUYIN 必填）：微信 js_code / 抖音 code。 */
    private String code;

    /** 平台手机号授权码（可选）：提供后可用于完成本服务账号绑定解析。 */
    private String phoneCode;
}
