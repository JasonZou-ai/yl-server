package cn.yl.common.idempotent;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 幂等标记（B1-2 / 子任务 rRR1Zn-rfVTRf）。
 *
 * <p>标注在写操作接口上后，需客户端携带 {@code Idempotency-Key} 请求头。同一 key 的重复请求不会重复执行， 而是直接返回首次执行结果 ——
 * 用于阻断「重复提交」「重复生成报告」「重复上报」 （PRD §7 幂等防重）。
 *
 * <p>幂等窗口默认 24 小时，可由 {@link #ttlSeconds()} 覆盖。
 */
@Documented
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Idempotent {

    /** 幂等键有效期（秒），默认 24 小时 */
    long ttlSeconds() default 24 * 3600L;

    /** 是否校验请求体摘要：启用后，同一 key 携带不同请求体会被拒绝，防止键复用导致串数据 */
    boolean verifyBody() default true;
}
