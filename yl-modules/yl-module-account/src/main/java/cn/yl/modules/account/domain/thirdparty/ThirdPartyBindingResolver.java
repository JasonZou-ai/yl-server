package cn.yl.modules.account.domain.thirdparty;

import java.util.Optional;

/**
 * 第三方身份 → 本服务账号的绑定解析端口（B2 / rpW1xZ）。
 *
 * <p>本期实现为**手机号绑定**（见 {@code PhoneHashBindingResolver}）：平台侧手机号 → HMAC-SHA256 摘要 → 命中 {@code
 * sys_user.phone_hash}。该方案不需要新增绑定表，但每次登录都依赖平台回传手机号授权。
 *
 * <p>⚠️ 开放项（ER-14 候选）：若要求「平台 openId 持久绑定、后续免手机号授权登录」，则须新增第三方账号绑定表， 属 DDL 变更，需走 ER 评审与 {@code
 * verify-schema.sh} 断言补充。
 */
public interface ThirdPartyBindingResolver {

    /**
     * 解析本服务登录名。
     *
     * @param identity 平台身份
     * @return 本服务登录名；未绑定平台账号时返回空
     */
    Optional<String> resolveUsername(ThirdPartyIdentity identity);
}
