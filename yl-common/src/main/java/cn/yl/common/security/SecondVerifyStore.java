package cn.yl.common.security;

/**
 * 二次验证凭据存储端口（B2 / ri91pT）。
 *
 * <p>敏感操作（导出/作废/解绑/查看明文）在权限校验通过后，仍需一次性、短时效的二次验证凭据，防重放。 由基础设施层用 Redis 实现（GETDEL
 * 保证「一次有效」）；应用/接口层仅依赖本端口，便于替换与测试。
 */
public interface SecondVerifyStore {

    /**
     * 登记一次性凭据。
     *
     * @param token 凭据串
     * @param userId 归属用户
     * @param ttlSeconds 有效期（秒）
     */
    void issue(String token, long userId, long ttlSeconds);

    /**
     * 原子消费一次性凭据：命中且归属匹配则立刻删除并返回 true；不存在/已用/过期/归属不匹配返回 false。
     *
     * @param token 凭据串
     * @param userId 当前用户
     * @return 是否校验通过
     */
    boolean consume(String token, long userId);
}
