package cn.yl.api.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口级权限码校验注解（四道闸·第二闸：授权）。
 *
 * <p>标注在 Controller 方法或类上，由 {@code PermissionAspect} 拦截：校验当前 {@link LoginUser} 的 perms 是否包含
 * 所需权限码。need_second_verify=1 的敏感权限点（导出/作废/解绑）仍需经第四闸二次验证（ri91pT）。
 *
 * <p>示例：{@code @RequiresPermission(PermissionCode.DATA_EXPORT)} 或 {@code @RequiresPermission(value
 * = {A, B}, logical = Logical.OR)}。
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresPermission {

    /** 所需权限码（见 PermissionCode 常量）。 */
    String[] value();

    /** 多权限码之间的判定逻辑：ALL=全部满足（默认），OR=任一满足。 */
    Logical logical() default Logical.ALL;
}
