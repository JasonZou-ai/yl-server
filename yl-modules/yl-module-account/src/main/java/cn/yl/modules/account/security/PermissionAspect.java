package cn.yl.modules.account.security;

import cn.yl.api.security.Logical;
import cn.yl.api.security.LoginUser;
import cn.yl.api.security.RequiresPermission;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.account.domain.SensitivePermissions;
import java.util.Arrays;
import lombok.RequiredArgsConstructor;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Before;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * 权限码校验切面（四道闸·第二闸：授权；敏感点联动第四闸）。
 *
 * <p>在标注 {@link RequiresPermission} 的方法执行前，从 SecurityContext 取出 {@link LoginUser}，校验其 perms 是否覆盖
 * 所需权限码；未登录抛 UNAUTHORIZED，权限不足抛 ROLE_NOT_PERMITTED。
 *
 * <p>若所需权限码中存在 {@code need_second_verify=1} 的敏感点（见 {@link SensitivePermissions}），进一步校验一次性二次
 * 验证凭据（{@value SecondVerifyGuard#HEADER}），未通过抛 SECOND_VERIFY_REQUIRED——即设计 §3「敏感点在第二闸通过后转第四闸」。
 *
 * <p>授权通过后另委派 {@link ArchiveAccessRateGuard} 做<b>档案类读操作的行为异常检测</b>（CR-M2-001 §2.5）：该守卫只告警、不拦截，
 * 且失败开放，切面此处仅保留一行委派，鉴权链路的判定语义不变。
 */
@Aspect
@Component
@RequiredArgsConstructor
public class PermissionAspect {

    private final SecondVerifyGuard secondVerifyGuard;

    private final ArchiveAccessRateGuard archiveAccessRateGuard;

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
        if (Arrays.stream(needed).anyMatch(SensitivePermissions::isSensitive)) {
            assertSecondVerified(loginUser);
        }
        // CR-M2-001 §2.5 旁路监控：档案类读操作的行为异常检测（只告警、不拦截、失败开放）
        archiveAccessRateGuard.onPermissionGranted(loginUser, needed);
    }

    /** 第四闸：敏感操作须持有一次性二次验证凭据。 */
    private void assertSecondVerified(LoginUser loginUser) {
        if (!secondVerifyGuard.verify(
                currentHeader(SecondVerifyGuard.HEADER), loginUser.userId())) {
            throw new BizException(
                    ErrorCode.SECOND_VERIFY_REQUIRED, "该操作需先完成二次验证（方式：SCAN/PHONE/FACE）");
        }
    }

    private String currentHeader(String name) {
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            return servletAttributes.getRequest().getHeader(name);
        }
        return null;
    }
}
