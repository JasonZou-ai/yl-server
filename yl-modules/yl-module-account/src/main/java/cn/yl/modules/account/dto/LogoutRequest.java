package cn.yl.modules.account.dto;

import lombok.Data;

/**
 * 退出登录入参（D-4，{@code POST /api/v1/auth/logout}）。
 *
 * <p>{@code refreshToken} <b>可选</b>：携带则同时吊销该 refresh（双令牌同时失效）；不携带则仅将当前 access 记入黑名单（refresh
 * 仍可用至自然过期）。请求体不含任何身份字段（由服务端取登录态）。
 */
@Data
public class LogoutRequest {

    /** 可选刷新令牌；携带则同时 {@code revoke}。 */
    private String refreshToken;
}
