package cn.yl.modules.account.security;

import cn.yl.api.security.Audit;
import cn.yl.modules.account.service.AuditService;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.AfterReturning;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

/**
 * 审计切面（四道闸·第四闸留痕，设计 §5）。
 *
 * <p>拦截 {@link Audit}，在方法<b>成功返回后</b>写入 {@code audit_log}；方法抛异常则不落「成功」留痕。
 */
@Aspect
@Component
@RequiredArgsConstructor
public class AuditLogAspect {

    private final AuditService auditService;

    @AfterReturning("@annotation(audit)")
    public void afterReturning(Audit audit) {
        auditService.record(audit.action(), audit.bizType(), audit.sensitive());
    }
}
