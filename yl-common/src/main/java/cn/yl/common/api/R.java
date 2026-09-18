package cn.yl.common.api;

import java.io.Serializable;
import lombok.Getter;

/**
 * 统一响应体（四端一致，承接 PRD §8「统一原则」）。
 *
 * <p>约定：成功 code=0；业务失败 code 见 {@link cn.yl.common.exception.ErrorCode}。
 *
 * @param <T> 业务数据类型
 */
@Getter
public class R<T> implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 业务状态码，0 表示成功 */
    private final int code;

    /** 提示信息 */
    private final String message;

    /** 业务数据 */
    private final T data;

    /** 链路追踪 ID，供日志与埋点关联（PRD §9） */
    private final String traceId;

    private R(int code, String message, T data, String traceId) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.traceId = traceId;
    }

    public static <T> R<T> ok(T data) {
        return new R<>(0, "success", data, TraceContext.currentTraceId());
    }

    public static <T> R<T> ok() {
        return ok(null);
    }

    public static <T> R<T> fail(int code, String message) {
        return new R<>(code, message, null, TraceContext.currentTraceId());
    }

    public boolean isSuccess() {
        return this.code == 0;
    }
}
