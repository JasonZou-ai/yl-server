package cn.yl.modules.account.service.thirdparty;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.account.domain.LoginChannel;
import cn.yl.modules.account.domain.thirdparty.LoginChannelAdapter;
import cn.yl.modules.account.domain.thirdparty.ThirdPartyAuthenticator;
import cn.yl.modules.account.domain.thirdparty.ThirdPartyBindingResolver;
import cn.yl.modules.account.domain.thirdparty.ThirdPartyIdentity;
import cn.yl.modules.account.dto.LoginRequest;
import java.util.Set;

/**
 * 平台授权渠道适配器基类（B2 / rpW1xZ）。
 *
 * <p>统一流程：**平台换证 → 绑定解析 → 返回本服务登录名**。子类只需提供对应平台的 {@link ThirdPartyAuthenticator}，无需重复流程代码。
 *
 * <p>安全口径：换证失败与未绑定均返回笼统的 {@code LOGIN_FAILED}，不区分「账号不存在」与「未绑定」，避免账号枚举； 平台侧标识（openId）不进异常文案与日志（ER-11
 * S2 禁采）。
 */
abstract class ThirdPartyLoginAdapter implements LoginChannelAdapter {

    private final ThirdPartyAuthenticator authenticator;
    private final ThirdPartyBindingResolver bindingResolver;

    ThirdPartyLoginAdapter(
            ThirdPartyAuthenticator authenticator, ThirdPartyBindingResolver bindingResolver) {
        this.authenticator = authenticator;
        this.bindingResolver = bindingResolver;
    }

    @Override
    public Set<LoginChannel> channels() {
        return Set.of(authenticator.channel());
    }

    @Override
    public boolean configured() {
        return authenticator.configured();
    }

    @Override
    public String resolveUsername(LoginRequest request) {
        ThirdPartyIdentity identity = authenticator.authenticate(request);
        return bindingResolver
                .resolveUsername(identity)
                .orElseThrow(
                        () ->
                                new BizException(
                                        ErrorCode.LOGIN_FAILED, "平台账号尚未绑定本服务账号（本期以手机号完成绑定）"));
    }
}
