package cn.yl.modules.account.service.thirdparty;

import cn.yl.modules.account.domain.thirdparty.ThirdPartyBindingResolver;
import org.springframework.stereotype.Component;

/** 微信渠道登录适配器（WECHAT）：换证走 {@link WechatThirdPartyAuthenticator}，绑定解析走手机号摘要。 */
@Component
public class WechatLoginAdapter extends ThirdPartyLoginAdapter {

    public WechatLoginAdapter(
            WechatThirdPartyAuthenticator authenticator,
            ThirdPartyBindingResolver bindingResolver) {
        super(authenticator, bindingResolver);
    }
}
