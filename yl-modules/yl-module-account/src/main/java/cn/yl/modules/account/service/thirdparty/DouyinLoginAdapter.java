package cn.yl.modules.account.service.thirdparty;

import cn.yl.modules.account.domain.thirdparty.ThirdPartyBindingResolver;
import org.springframework.stereotype.Component;

/** 抖音渠道登录适配器（DOUYIN）：换证走 {@link DouyinThirdPartyAuthenticator}，绑定解析走手机号摘要。 */
@Component
public class DouyinLoginAdapter extends ThirdPartyLoginAdapter {

    public DouyinLoginAdapter(
            DouyinThirdPartyAuthenticator authenticator,
            ThirdPartyBindingResolver bindingResolver) {
        super(authenticator, bindingResolver);
    }
}
