package cn.yl.modules.account.service;

import cn.yl.api.security.LoginUser;
import cn.yl.modules.account.domain.entity.AuditLog;
import cn.yl.modules.account.mapper.AuditLogMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 审计留痕服务（B2 / ri91pT，设计 §5）。
 *
 * <p>写 {@code audit_log}，{@code retain_until = now + 3 年}（ER-03）。{@code detail} 仅记脱敏上下文，禁止写明文敏感信息。
 *
 * <p>约定：{@code sensitive=true} 的留痕由 {@code AuditLogAspect} 在方法成功返回后触发；能走到该步意味着第四闸二次验证已 通过，故 {@code
 * second_verify=1}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    /** 审计日志保留期（年，ER-03）。 */
    private static final int RETAIN_YEARS = 3;

    private static final int MAX_UA_LENGTH = 255;

    private final AuditLogMapper auditLogMapper;

    /**
     * 记录一条审计日志。
     *
     * @param action 操作类型（EXPORT/VOID/UNBIND/REVIEW/ARCHIVE…）
     * @param bizType 业务类型（可空）
     * @param sensitive 是否敏感操作
     */
    public void record(String action, String bizType, boolean sensitive) {
        record(action, bizType, sensitive, null);
    }

    /**
     * 记录一条审计日志（带脱敏上下文）。
     *
     * @param action 操作类型
     * @param bizType 业务类型（可空）
     * @param sensitive 是否敏感操作
     * @param detail 已脱敏的操作上下文 JSON（可空）
     */
    public void record(String action, String bizType, boolean sensitive, String detail) {
        AuditLog auditLog = new AuditLog();
        auditLog.setAction(action);
        auditLog.setBizType(bizType == null || bizType.isBlank() ? null : bizType);
        auditLog.setSensitive(sensitive ? 1 : 0);
        auditLog.setSecondVerify(sensitive ? 1 : 0);
        auditLog.setDetail(detail);
        auditLog.setRetainUntil(LocalDateTime.now().plusYears(RETAIN_YEARS));

        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser user) {
            auditLog.setOperatorId(user.userId());
        }
        HttpServletRequest request = currentRequest();
        if (request != null) {
            auditLog.setIp(clientIp(request));
            String ua = request.getHeader("User-Agent");
            auditLog.setUa(ua == null ? null : truncate(ua, MAX_UA_LENGTH));
        }
        auditLogMapper.insert(auditLog);
    }

    private HttpServletRequest currentRequest() {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest();
        }
        return null;
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
