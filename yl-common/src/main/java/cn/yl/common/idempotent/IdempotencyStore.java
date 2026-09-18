package cn.yl.common.idempotent;

/**
 * 幂等存储端口（B1-2 / 子任务 rfVTRf）。
 *
 * <p>由基础设施层实现（MVP 用 Redis；亦可加一层 DB 兜底持久化 idempotent_record，见 B1-4 表结构）。 应用/接口层仅依赖本端口，便于替换与测试。
 */
public interface IdempotencyStore {

    /**
     * 尝试占用幂等键。
     *
     * @param key 幂等键
     * @param ttlSeconds 有效期（秒）
     * @return true 表示首次占用成功（可继续执行）；false 表示键已存在（重复请求）
     */
    boolean tryAcquire(String key, long ttlSeconds);

    /**
     * 读取已完成请求的响应快照。
     *
     * @param key 幂等键
     * @return 首次响应的 JSON 字符串；尚在处理中或不存在时返回 null
     */
    String getResponse(String key);

    /**
     * 写入响应快照，供后续重复请求直接返回。
     *
     * @param key 幂等键
     * @param responseJson 首次响应体（已序列化）
     * @param ttlSeconds 有效期（秒）
     */
    void saveResponse(String key, String responseJson, long ttlSeconds);

    /** 释放幂等键（执行失败时调用，允许客户端用同一 key 重试）。 */
    void release(String key);

    /** 标记该键已进入处理中（尚未产生响应），重复请求应返回「处理中」而非重复执行。 */
    boolean markProcessing(String key, long ttlSeconds);
}
