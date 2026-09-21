package cn.yl.api.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 敏感操作审计注解（四道闸·第四闸留痕，设计 §5）。
 *
 * <p>标注在需要留痕的业务方法上，由 {@code AuditLogAspect} 在方法成功返回后写入 {@code audit_log}，保留期 3 年（ER-03）。 {@code
 * sensitive=true} 表示敏感操作（导出/作废/解绑），须配合二次验证（ri91pT）方可执行。
 *
 * <p>示例：{@code @Audit(action = "EXPORT", bizType = "EVAL_ORDER", sensitive = true)}。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Audit {

    /** 操作类型，写入 {@code audit_log.action}，如 EXPORT/VOID/UNBIND/REVIEW/ARCHIVE。 */
    String action();

    /** 业务类型，写入 {@code audit_log.biz_type}，如 EVAL_ORDER/ELDER。留空表示不关联具体业务对象。 */
    String bizType() default "";

    /** 是否敏感操作：true 表示需二次验证 + 强留痕。 */
    boolean sensitive() default false;
}
