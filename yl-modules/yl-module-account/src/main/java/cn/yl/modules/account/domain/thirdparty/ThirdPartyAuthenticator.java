package cn.yl.modules.account.domain.thirdparty;

import cn.yl.modules.account.domain.LoginChannel;
import cn.yl.modules.account.dto.LoginRequest;

/**
 * 第三方平台换证端口（B2 / rpW1xZ）。
 *
 * <p>职责单一：用平台登录码换取 {@link ThirdPartyIdentity}。**不负责**本服务账号解析（见 {@link
 * ThirdPartyBindingResolver}），两者分离便于分别替换与单元测试。
 *
 * <p>各平台实现放在本模块，因为换证需要 HTTP 客户端与 JSON 序列化，而它们是经 {@code yl-api} 传递的 Web 依赖；
 * 若后续统一收敛到基础设施层，只需迁移实现、无需改动调用方。
 */
public interface ThirdPartyAuthenticator {

    /** 所属登录渠道。 */
    LoginChannel channel();

    /** 平台侧配置是否齐备（app-id / app-secret 等）。未齐备时调用方必须**明确拒绝**，不得静默降级。 */
    boolean configured();

    /**
     * 用平台登录码换取平台身份。
     *
     * @param request 登录请求（使用 {@code code} / {@code phoneCode}）
     * @return 平台身份
     */
    ThirdPartyIdentity authenticate(LoginRequest request);
}
