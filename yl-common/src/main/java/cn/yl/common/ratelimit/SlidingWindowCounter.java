package cn.yl.common.ratelimit;

/**
 * 滑动窗口计数器端口（CR-M2-001 §2.5 档案异常访问告警）。
 *
 * <p>与 {@link RateLimiter} 的区别：{@code RateLimiter} 是<b>固定（自然）窗口</b>分桶，窗口边界处会出现 2 倍突发；
 * 本端口是<b>滑动窗口</b>——以「当前时刻往前推 windowSeconds」为统计区间，窗口内每次记录都精确参与计数。
 *
 * <p>CR-M2-001 明确要求「单小时调用次数」按<b>滑动 1 小时（非自然小时）</b>统计，故不复用固定窗口实现。
 *
 * <p>由基础设施层用 Redis 实现（ZSET 有序集合 + Lua 脚本保证「修剪—写入—计数」原子）；应用层仅依赖本端口，便于替换与测试。
 */
public interface SlidingWindowCounter {

    /**
     * 记录一次事件，并返回<b>含本次在内</b>的窗口内累计次数。
     *
     * <p>幂等性边界：同名 {@code dimension} 下同毫秒的多次调用互不覆盖（实现须为每次调用生成唯一成员），否则高频访问会被少计。
     *
     * @param dimension 计数维度（实现会拼接为独立键，调用方须自带业务前缀，如 {@code yl:arch:rate:1001}）
     * @param windowSeconds 滑动窗口长度（秒）
     * @return 窗口内累计次数；计数不可用时返回 {@code 0}（调用方须按「不误告警」处理）
     */
    long recordAndCount(String dimension, long windowSeconds);

    /**
     * 尝试在窗口内占用一个<b>一次性</b>名额（用于告警去重，避免同一窗口内反复告警）。
     *
     * @param dimension 计数维度
     * @param windowSeconds 名额有效期（秒）
     * @return 占用成功返回 {@code true}；窗口内已被占用返回 {@code false}
     */
    boolean tryAcquireOnce(String dimension, long windowSeconds);
}
