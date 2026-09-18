package cn.yl.common.ratelimit;

/**
 * 限流端口（B1-2 / 子任务 rPX9p2）。
 *
 * <p>维度可自由组合，例如 {@code ip:1.2.3.4}、{@code user:1001}、{@code api:eval:submit}。 由基础设施层用 Redis
 * 实现，保证多实例下计数一致。
 */
public interface RateLimiter {

    /**
     * 尝试获取一次配额。
     *
     * @param dimension 限流维度键
     * @param limit 窗口内允许的最大次数
     * @param windowSeconds 窗口大小（秒）
     * @return true 允许通过；false 触发限流
     */
    boolean tryAcquire(String dimension, int limit, int windowSeconds);
}
