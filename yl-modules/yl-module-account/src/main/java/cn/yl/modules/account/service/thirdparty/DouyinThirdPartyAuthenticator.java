package cn.yl.modules.account.service.thirdparty;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.account.domain.LoginChannel;
import cn.yl.modules.account.domain.thirdparty.ThirdPartyAuthenticator;
import cn.yl.modules.account.domain.thirdparty.ThirdPartyIdentity;
import cn.yl.modules.account.dto.LoginRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 抖音小程序换证（B2 / rpW1xZ）。
 *
 * <p>采用平台 v2 换证接口：以 {@code code} 换 {@code openid}。抖音的**手机号获取接口需在平台侧单独申请权限**， 且接口形态随平台版本调整，故本实现将
 * {@code phone-url} 留空默认：未配置时若请求带 {@code phoneCode}，
 * 会明确拒绝并提示「手机号解析未配置」，而不是静默返回未绑定，避免把「配置缺失」误报成「用户未绑定」。
 */
@Component
public class DouyinThirdPartyAuthenticator implements ThirdPartyAuthenticator {

    private static final String PLATFORM = "DOUYIN";
    private static final String DEFAULT_SESSION_URL =
            "https://developer.toutiao.com/api/apps/v2/jscode2session";

    private final ThirdPartyHttpSupport http;
    private final String appId;
    private final String appSecret;
    private final String sessionUrl;
    private final String phoneUrl;

    public DouyinThirdPartyAuthenticator(
            ThirdPartyHttpSupport http,
            @Value("${yl.security.third-party.douyin.app-id:}") String appId,
            @Value("${yl.security.third-party.douyin.app-secret:}") String appSecret,
            @Value("${yl.security.third-party.douyin.session-url:}") String sessionUrl,
            @Value("${yl.security.third-party.douyin.phone-url:}") String phoneUrl) {
        this.http = http;
        this.appId = appId;
        this.appSecret = appSecret;
        this.sessionUrl = notBlank(sessionUrl) ? sessionUrl : DEFAULT_SESSION_URL;
        this.phoneUrl = phoneUrl;
    }

    @Override
    public LoginChannel channel() {
        return LoginChannel.DOUYIN;
    }

    @Override
    public boolean configured() {
        return notBlank(appId) && notBlank(appSecret);
    }

    @Override
    public ThirdPartyIdentity authenticate(LoginRequest request) {
        String code = request.getCode();
        if (!notBlank(code)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "抖音登录须提供 code");
        }
        JsonNode response =
                http.postJson(
                        sessionUrl,
                        http.toJson(Map.of("appid", appId, "secret", appSecret, "code", code)));
        String openId = ThirdPartyHttpSupport.path(response, "data", "openid");
        if (!notBlank(openId)) {
            String errNo = ThirdPartyHttpSupport.text(response, "err_no");
            String errTips = ThirdPartyHttpSupport.text(response, "err_tips");
            throw new BizException(
                    ErrorCode.LOGIN_FAILED,
                    "抖音登录换证失败："
                            + (errNo == null ? "" : "err_no=" + errNo)
                            + (errTips == null ? "" : " " + errTips));
        }
        return new ThirdPartyIdentity(PLATFORM, openId, resolvePhone(request.getPhoneCode()));
    }

    /** 拼装抖音手机号解析；未配置接口时明确拒绝（不伪装成「未绑定」）。 */
    private String resolvePhone(String phoneCode) {
        if (!notBlank(phoneCode)) {
            return null;
        }
        if (!notBlank(phoneUrl)) {
            throw new BizException(
                    ErrorCode.LOGIN_FAILED,
                    "抖音手机号解析接口未配置（yl.security.third-party.douyin.phone-url），暂无法完成绑定");
        }
        JsonNode response =
                http.postJson(
                        phoneUrl,
                        http.toJson(
                                Map.of("appid", appId, "secret", appSecret, "code", phoneCode)));
        String phone = ThirdPartyHttpSupport.text(response, "phone");
        if (!notBlank(phone)) {
            phone = ThirdPartyHttpSupport.path(response, "data", "phone");
        }
        return phone;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
