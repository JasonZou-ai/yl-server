package cn.yl.common.api;

/**
 * 链路追踪上下文。
 *
 * <p>MVP 阶段基于 ThreadLocal 简化实现，网关/过滤器写入 X-Trace-Id 后此处读取； 后续接入 SkyWalking/OTel 时替换实现即可，调用方无感。
 */
public final class TraceContext {

    private static final ThreadLocal<String> TRACE_ID = new ThreadLocal<>();

    private TraceContext() {}

    public static void set(String traceId) {
        TRACE_ID.set(traceId);
    }

    public static String currentTraceId() {
        String traceId = TRACE_ID.get();
        return traceId == null ? "" : traceId;
    }

    public static void clear() {
        TRACE_ID.remove();
    }
}
