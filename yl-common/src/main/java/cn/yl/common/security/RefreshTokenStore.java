package cn.yl.common.security;

/**
 * 刷新令牌存储端口（B2 / rpW1xZ）。
 *
 * <p>四端登录统一换发本服务双令牌，refresh 存于 Redis 且可被吊销；换发新 access 前校验其有效性与归属。 由基础设施层实现（Redis）；应用/接口层仅依赖本端口。
 */
public interface RefreshTokenStore {

    /**
     * 登记刷新令牌。
     *
     * @param token 刷新令牌
     * @param userId 归属用户
     * @param ttlSeconds 有效期（秒，与令牌 TTL 一致）
     */
    void save(String token, long userId, long ttlSeconds);

    /**
     * 查询刷新令牌归属用户。
     *
     * @param token 刷新令牌
     * @return 归属用户 ID；不存在/已吊销/过期返回 {@code null}
     */
    Long findUserId(String token);

    /** 吊销刷新令牌（注销/轮换）。 */
    void revoke(String token);
}
