package cn.yl.modules.account.security;

import cn.yl.api.security.Logical;
import cn.yl.api.security.LoginUser;
import cn.yl.api.security.RequiresPermission;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import java.util.Arrays;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * 权限码校验切面（四道闸·第二闸：授权）。
 *
 * <p>在标注 {@link RequiresPermission} 的方法执行前，从 SecurityContext 取出 {@link LoginUser}，校验其 perms 是否覆盖
 * 所需权限码；未登录抛 UNAUTHORIZED，权限不足抛 ROLE_NOT_PERMITTED。
 *
 * <p>敏感权限点（need_second_verify=1）仍须经第四闸二次验证（ri91pT 的 SecondVerifyGuard）后才可放行。
 */
@Aspect
@Component
public class PermissionAspect {

    @Before("@annotation(requiresPermission)")
    public void checkPermission(RequiresPermission requiresPermission) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof LoginUser loginUser)) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        String[] needed = requiresPermission.value();
        boolean granted =
                requiresPermission.logical() == Logical.OR
                        ? Arrays.stream(needed).anyMatch(loginUser::hasPermission)
                        : Arrays.stream(needed).allMatch(loginUser::hasPermission);
        if (!granted) {
            throw new BizException(ErrorCode.ROLE_NOT_PERMITTED);
        }
    }
}
