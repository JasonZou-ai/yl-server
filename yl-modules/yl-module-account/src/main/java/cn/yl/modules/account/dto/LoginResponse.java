package cn.yl.modules.account.dto;

import java.util.List;
import lombok.Data;

/** 登录/刷新响应：统一换发的双令牌 + 账号档案摘要（四端一致）。 */
@Data
public class LoginResponse {

    private String accessToken;
    private String refreshToken;

    /** 令牌类型，固定 Bearer。 */
    private String tokenType;

    /** access 有效期（秒）。 */
    private long expiresIn;

    private long userId;
    private List<String> roles;

    /** 数据域口径（1-本人 2-本机构 3-全量 4-只读全局）。 */
    private int dataScope;
}
