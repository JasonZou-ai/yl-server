package cn.yl.common.security;

/**
 * 访问令牌黑名单端口（D-4 / `authLogout`）。
 *
 * <p>与 {@link RefreshTokenStore} 同范式：接口定义在 {@code yl-common}，Redis 实现置于 {@code
 * yl-infrastructure}；应用/接口层 仅依赖本端口。
 *
 * <p><b>键口径</b>：实现层以 {@code yl:bl:at:<sha256Hex(token)>} 为键（值 = userId），<b>不存原令牌</b>（防 Redis
 * 泄漏即令牌泄漏）。 调用方一律传<b>原令牌</b>，散列由实现层完成，保证 {@link #add} 与 {@link #isBlacklisted} 的键空间一致。
 *
 * <p><b>用途</b>：{@code POST /auth/logout} 把当前 access 令牌登记为黑名单（TTL = 令牌剩余有效期）；{@code JwtAuthFilter} 在
 * 令牌验签成功后、装配登录态之前查询本端口，命中即按未认证短路（401 + {@code R.code=10002}）。
 */
public interface TokenBlacklistStore {

    /**
     * 将访问令牌登记为黑名单。
     *
     * @param token 原访问令牌（实现层内部散列；空/空白忽略）
     * @param userId 令牌归属用户（作为值保留，便于审计/排障）
     * @param ttlSeconds 存活秒数（取令牌剩余有效期；{@code <=0} 表示已过期，无需登记）
     */
    void add(String token, long userId, long ttlSeconds);

    /**
     * 令牌是否已被拉黑。
     *
     * @param token 原访问令牌
     * @return 命中返回 {@code true}；未命中或令牌为空返回 {@code false}
     */
    boolean isBlacklisted(String token);
}
