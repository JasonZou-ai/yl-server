package cn.yl.common.sms;

/**
 * 短信验证码与失败计数存储端口（P2-8-A，设计 §4.4）。
 *
 * <p>键结构（全摘要，零明文手机号）：
 *
 * <ul>
 *   <li>{@code yl:sms:code:{purpose}:{phoneHash}} → {@code {sha256(code)}:{attemptCount}}，TTL 由
 *       调用方给定（默认 300s）
 *   <li>{@code yl:sms:err:{purpose}:{phoneHash}} → 失败累计次数（≥5 触发锁定），TTL 由调用方给定（默认 900s）
 * </ul>
 *
 * <p>调用方传入的 {@code phoneHash} 必须是 HMAC-SHA256 摘要（{@code SensitiveFieldCodec#hashForSearch}），
 * <b>不得</b>是明文手机号。
 */
public interface SmsCodeStore {

    /**
     * 保存验证码（值只存 {@code sha256(code)}，不落明文）。
     *
     * @param phoneHash 手机号摘要
     * @param purpose 用途
     * @param code 验证码明文
     * @param ttlSeconds 有效期（秒）
     */
    void save(String phoneHash, SmsPurpose purpose, String code, long ttlSeconds);

    /**
     * 原子「比对并焚毁」：值存在且与 {@code code} 摘要相等时删除并返回 {@code true}（一次性核销）。
     *
     * <p>★ 实现<b>必须</b>用单次往返的 Lua 脚本完成「GET 比对 → 相等则 DEL」，不得拆成「先 GET 再 DEL」两步，
     * 否则并发下同一验证码可被双消费（TOCTOU）。
     *
     * @return 命中并已焚毁返回 {@code true}；不存在 / 不匹配返回 {@code false}
     */
    boolean consumeIfMatches(String phoneHash, SmsPurpose purpose, String code);

    /**
     * 累加一次失败计数并返回累计值（首次累加时设置过期）。
     *
     * @param lockTtlSeconds 失败计数键的过期（秒）
     * @return 累计失败次数
     */
    long incrementFailure(String phoneHash, SmsPurpose purpose, long lockTtlSeconds);

    /** 当前失败累计次数；键不存在返回 {@code 0}。 */
    long failureCount(String phoneHash, SmsPurpose purpose);

    /** 校验成功后清零失败计数（删除失败计数键）。 */
    void clearFailure(String phoneHash, SmsPurpose purpose);
}
