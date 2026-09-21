package cn.yl.modules.account.security;

import cn.yl.api.security.LoginUser;
import cn.yl.api.security.RequiresPermission;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.account.domain.DataScope;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import java.util.Set;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 只读全局（data_scope=4，监管）写操作守卫（四道闸·第三闸补充）。
 *
 * <p>监管账号只读全局：对非安全方法（非 GET/HEAD/OPTIONS/TRACE）默认拒绝。唯一例外——当目标方法以 {@link RequiresPermission}
 * 显式声明权限码、且当前账号确实持有该码时放行（对应矩阵「监管数据上报 supervise:report:submit」等被显式授予的写权限），其余写操作一律 403。
 *
 * <p>本规则与权限矩阵一致：监管不常驻持有任何写权限点，写能力完全由矩阵逐格约束显现，避免路径级黑名单。
 */
public class DataScopeReadOnlyGuardInterceptor implements HandlerInterceptor {

    private static final Set<String> SAFE_METHODS = Set.of("GET", "HEAD", "OPTIONS", "TRACE");

    @Override
    public boolean preHandle(
            HttpServletRequest request, HttpServletResponse response, Object handler) {
        LoginUser user = currentUser();
        if (user == null || user.dataScope() != DataScope.READONLY_GLOBAL.code()) {
            return true;
        }
        if (SAFE_METHODS.contains(request.getMethod())) {
            return true;
        }
        if (handler instanceof HandlerMethod handlerMethod
                && hasGrantedPermission(handlerMethod, user)) {
            return true;
        }
        throw new BizException(ErrorCode.FORBIDDEN, "只读账号（监管）不可执行该写操作");
    }

    /** 目标方法/类上声明的权限码中，是否有一个被当前账号持有。 */
    private boolean hasGrantedPermission(HandlerMethod handlerMethod, LoginUser user) {
        RequiresPermission annotation = handlerMethod.getMethodAnnotation(RequiresPermission.class);
        if (annotation == null && handlerMethod.getBeanType() != null) {
            annotation = handlerMethod.getBeanType().getAnnotation(RequiresPermission.class);
        }
        if (annotation == null) {
            return false;
        }
        return Arrays.stream(annotation.value()).anyMatch(user::hasPermission);
    }

    private LoginUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof LoginUser loginUser)) {
            return null;
        }
        return loginUser;
    }
}
