package cn.yl.api.support;

import cn.yl.common.api.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * 链路追踪过滤器（B1-2 基础设施）。
 *
 * <p>为每个请求生成或透传 {@code X-Trace-Id}，写入 {@link TraceContext} 与 MDC，使日志、统一响应体、 埋点（PRD §9）可关联同一请求。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** 追踪头名称，四端一致 */
    public static final String TRACE_HEADER = "X-Trace-Id";

    private static final String MDC_KEY = "traceId";

    /**
     * 追踪 ID 白名单：仅允许字母、数字、连字符、下划线，长度 8–64。
     *
     * <p>客户端可自带该头，若原样回写响应头则存在 CRLF 头注入风险（可伪造 Set-Cookie 等）， 故必须整串匹配校验，不合法一律丢弃。
     */
    private static final Pattern TRACE_ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String traceId = sanitizeTraceId(request.getHeader(TRACE_HEADER));
        if (traceId == null) {
            traceId = UUID.randomUUID().toString().replace("-", "");
        }
        TraceContext.set(traceId);
        MDC.put(MDC_KEY, traceId);
        response.setHeader(TRACE_HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 清理 ThreadLocal，防止线程池复用导致串号
            TraceContext.clear();
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * 校验外部传入的追踪 ID。
     *
     * <p>取值必须整串匹配 {@link #TRACE_ID_PATTERN}，从而阻断经请求头注入 CRLF、伪造响应头或污染日志的行为。
     *
     * @return 合法则返回原值，含非法字符或长度越界返回 {@code null}
     */
    private static String sanitizeTraceId(String raw) {
        if (raw == null || !TRACE_ID_PATTERN.matcher(raw).matches()) {
            return null;
        }
        return raw;
    }
}
