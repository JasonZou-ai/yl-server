package cn.yl.api.security;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 登录态取值工具（CR-M2-002）：<b>业务侧操作者的唯一合法来源</b>。
 *
 * <p>收口背景：复核/发布/作废/照护确认原先从**请求体** {@code operatorId} 取操作者，导致「任何持合法令牌的账号只要知道 orderId
 * 就能以他人名义发布/作废他人评估单」（实测：评估员令牌传机构管理员 ID 即成功）。现口径为：
 *
 * <ul>
 *   <li>操作者一律由本类从 SecurityContext 取，<b>请求体身份字段一律不可信</b>；
 *   <li>越权判定交给第二闸 {@link RequiresPermission}（权限码）与第三闸 {@code DataScopePermissionHandler}（数据域）。
 * </ul>
 *
 * <p>取不到登录主体时抛 {@link ErrorCode#UNAUTHORIZED}，不返回 null 供调用方"兜底"，避免兜底逻辑静默降级为匿名操作。
 */
public final class SecurityUtils {

    private SecurityUtils() {}

    /** 当前登录主体；未建立登录态返回 {@code null}（仅供需要区分的场景，业务侧请用 {@link #requireUserId()}）。 */
    public static LoginUser currentUserOrNull() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser user) {
            return user;
        }
        return null;
    }

    /** 当前登录主体；未登录抛 UNAUTHORIZED。 */
    public static LoginUser requireUser() {
        LoginUser user = currentUserOrNull();
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return user;
    }

    /** 当前登录用户 ID；未登录抛 UNAUTHORIZED。 */
    public static long requireUserId() {
        return requireUser().userId();
    }
}
