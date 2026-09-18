package cn.yl.api.support;

import cn.yl.common.api.R;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.common.idempotent.IdempotencyStore;
import cn.yl.common.idempotent.Idempotent;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 幂等切面（B1-2 / 子任务 rfVTRf）。
 *
 * <p>拦截 {@link Idempotent} 标注的接口，按 {@code Idempotency-Key} 请求头去重：
 *
 * <ol>
 *   <li>已有响应快照 → 直接回放首次结果（不重复执行）
 *   <li>正在处理中 → 返回 {@link ErrorCode#IDEMPOTENT_REPLAY}
 *   <li>首次请求 → 占用键 → 执行 → 落快照
 *   <li>执行异常 → 释放键，允许客户端用同一键重试
 * </ol>
 *
 * <p>{@code verifyBody=true} 时额外校验请求体摘要，防止同一幂等键被复用于不同业务数据（串数据风险）。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification =
                                "IdempotencyStore 与 ObjectMapper 均为 Spring 容器管理的线程安全共享 Bean，"
                                        + "构造器注入是标准 DI 用法"))
public class IdempotentAspect {

    private static final String HEADER = "Idempotency-Key";
    private static final String OWNER_SUFFIX = "#owner";

    private final IdempotencyStore idempotencyStore;
    private final ObjectMapper objectMapper;

    @Around("@annotation(idempotent)")
    public Object around(ProceedingJoinPoint joinPoint, Idempotent idempotent) throws Throwable {
        HttpServletRequest request = currentRequest();
        if (request == null) {
            return joinPoint.proceed();
        }
        String rawKey = request.getHeader(HEADER);
        if (rawKey == null || rawKey.isBlank()) {
            throw new BizException(ErrorCode.IDEMPOTENT_KEY_MISSING);
        }
        long ttl = idempotent.ttlSeconds();
        String idemKey = request.getRequestURI() + ":" + rawKey;

        String cached = idempotencyStore.getResponse(idemKey);
        if (cached != null) {
            log.info("幂等命中，回放首次响应 idemKey={}", idemKey);
            return objectMapper.readValue(cached, R.class);
        }

        if (idempotent.verifyBody()) {
            verifyBodyOwner(idemKey, joinPoint.getArgs(), ttl);
        }

        if (!idempotencyStore.tryAcquire(idemKey, ttl)) {
            throw new BizException(ErrorCode.IDEMPOTENT_REPLAY, "相同请求正在处理中，请勿重复提交");
        }

        try {
            Object result = joinPoint.proceed();
            if (result instanceof R<?> r) {
                idempotencyStore.saveResponse(idemKey, objectMapper.writeValueAsString(r), ttl);
            }
            return result;
        } catch (Throwable ex) {
            // 执行失败需释放，否则客户端无法用同一键重试
            idempotencyStore.release(idemKey);
            idempotencyStore.release(idemKey + OWNER_SUFFIX);
            throw ex;
        }
    }

    /** 校验同一幂等键是否携带一致的请求体摘要。 */
    private void verifyBodyOwner(String idemKey, Object[] args, long ttl) {
        String ownerKey = idemKey + OWNER_SUFFIX;
        String hash = bodyHash(args);
        if (hash == null) {
            return;
        }
        if (idempotencyStore.tryAcquire(ownerKey, ttl)) {
            idempotencyStore.saveResponse(ownerKey, hash, ttl);
            return;
        }
        String previous = idempotencyStore.getResponse(ownerKey);
        if (previous != null && !previous.equals(hash)) {
            throw new BizException(ErrorCode.IDEMPOTENT_REPLAY, "幂等键已被不同请求体使用");
        }
    }

    private String bodyHash(Object[] args) {
        try {
            StringBuilder sb = new StringBuilder();
            for (Object arg : args) {
                if (arg == null
                        || arg instanceof ServletRequest
                        || arg instanceof ServletResponse) {
                    continue;
                }
                sb.append(objectMapper.writeValueAsString(arg));
            }
            return sha256Hex(sb.toString());
        } catch (Exception e) {
            log.warn("请求体摘要计算失败，跳过请求体一致性校验", e);
            return null;
        }
    }

    private String sha256Hex(String text) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    private HttpServletRequest currentRequest() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            return attrs.getRequest();
        }
        return null;
    }
}
