package cn.yl.modules.account.service.thirdparty;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.account.domain.LoginChannel;
import cn.yl.modules.account.domain.thirdparty.ThirdPartyAuthenticator;
import cn.yl.modules.account.domain.thirdparty.ThirdPartyIdentity;
import cn.yl.modules.account.dto.LoginRequest;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 微信小程序换证（B2 / rpW1xZ）。
 *
 * <p>两步走，与平台能力一一对应：
 *
 * <ol>
 *   <li>{@code sns/jscode2session}：用 {@code code} 换 {@code openid}——只证明「同一个小程序用户」，不含手机号；
 *   <li>{@code wxa/business/getuserphonenumber}：用 {@code phoneCode}（按钮授权返回）换手机号——凭此完成绑定解析。
 * </ol>
 *
 * <p>因此登录请求需同时携带 {@code code}；若未携带 {@code phoneCode}，换证仍成功但手机号为空，绑定解析会判定为「未绑定」。 access_token 本地缓存并按
 * {@code expires_in} 提前 60s 失效，避免每次登录都取票。
 *
 * <p>配置齐备（app-id + app-secret）才认为渠道可用；endpoint 均可覆盖，便于联调/沙箱。
 */
@Component
public class WechatThirdPartyAuthenticator implements ThirdPartyAuthenticator {

    private static final String PLATFORM = "WECHAT";
    private static final String DEFAULT_SESSION_URL =
            "https://api.weixin.qq.com/sns/jscode2session";
    private static final String DEFAULT_TOKEN_URL = "https://api.weixin.qq.com/cgi-bin/token";
    private static final String DEFAULT_PHONE_URL =
            "https://api.weixin.qq.com/wxa/business/getuserphonenumber";
    private static final long TOKEN_SAFETY_MARGIN_SECONDS = 60L;

    private final ThirdPartyHttpSupport http;
    private final String appId;
    private final String appSecret;
    private final String sessionUrl;
    private final String tokenUrl;
    private final String phoneUrl;

    private final AtomicReference<CachedToken> cachedToken = new AtomicReference<>();

    public WechatThirdPartyAuthenticator(
            ThirdPartyHttpSupport http,
            @Value("${yl.security.third-party.wechat.app-id:}") String appId,
            @Value("${yl.security.third-party.wechat.app-secret:}") String appSecret,
            @Value("${yl.security.third-party.wechat.session-url:}") String sessionUrl,
            @Value("${yl.security.third-party.wechat.token-url:}") String tokenUrl,
            @Value("${yl.security.third-party.wechat.phone-url:}") String phoneUrl) {
        this.http = http;
        this.appId = appId;
        this.appSecret = appSecret;
        this.sessionUrl = orDefault(sessionUrl, DEFAULT_SESSION_URL);
        this.tokenUrl = orDefault(tokenUrl, DEFAULT_TOKEN_URL);
        this.phoneUrl = orDefault(phoneUrl, DEFAULT_PHONE_URL);
    }

    @Override
    public LoginChannel channel() {
        return LoginChannel.WECHAT;
    }

    @Override
    public boolean configured() {
        return notBlank(appId) && notBlank(appSecret);
    }

    @Override
    public ThirdPartyIdentity authenticate(LoginRequest request) {
        String code = request.getCode();
        if (!notBlank(code)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "微信登录须提供 code");
        }
        String sessionUri =
                sessionUrl
                        + "?appid="
                        + ThirdPartyHttpSupport.enc(appId)
                        + "&secret="
                        + ThirdPartyHttpSupport.enc(appSecret)
                        + "&js_code="
                        + ThirdPartyHttpSupport.enc(code)
                        + "&grant_type=authorization_code";
        JsonNode session = http.getJson(sessionUri);
        String openId = ThirdPartyHttpSupport.text(session, "openid");
        if (!notBlank(openId)) {
            // 刻意不回显 errmsg 细节之外的平台原文；openId/session_key 一律不进日志与异常文案
            throw new BizException(ErrorCode.LOGIN_FAILED, "微信登录换证失败：" + safeErrMsg(session));
        }
        return new ThirdPartyIdentity(PLATFORM, openId, resolvePhone(request.getPhoneCode()));
    }

    /** 用手机号授权码换手机号；未提供授权码则返回 null（不阻断换证）。 */
    private String resolvePhone(String phoneCode) {
        if (!notBlank(phoneCode)) {
            return null;
        }
        String accessToken = accessToken();
        JsonNode response =
                http.postJson(
                        phoneUrl + "?access_token=" + ThirdPartyHttpSupport.enc(accessToken),
                        http.toJson(Map.of("code", phoneCode)));
        String phone = ThirdPartyHttpSupport.path(response, "phone_info", "purePhoneNumber");
        if (!notBlank(phone)) {
            phone = ThirdPartyHttpSupport.path(response, "phone_info", "phoneNumber");
        }
        return phone;
    }

    /** 取 access_token（带内存缓存）。 */
    private String accessToken() {
        CachedToken cached = cachedToken.get();
        long now = System.currentTimeMillis();
        if (cached != null && cached.expireAtMillis() > now) {
            return cached.value();
        }
        JsonNode tokenResponse =
                http.getJson(
                        tokenUrl
                                + "?grant_type=client_credential&appid="
                                + ThirdPartyHttpSupport.enc(appId)
                                + "&secret="
                                + ThirdPartyHttpSupport.enc(appSecret));
        String token = ThirdPartyHttpSupport.text(tokenResponse, "access_token");
        if (!notBlank(token)) {
            throw new BizException(
                    ErrorCode.LOGIN_FAILED, "微信 access_token 获取失败：" + safeErrMsg(tokenResponse));
        }
        long expiresIn = tokenResponse.path("expires_in").asLong(7200L);
        long expireAt = now + Math.max(0L, expiresIn - TOKEN_SAFETY_MARGIN_SECONDS) * 1000L;
        cachedToken.set(new CachedToken(token, expireAt));
        return token;
    }

    /** 平台错误码（仅 errcode/errmsg，不含任何标识）。 */
    private static String safeErrMsg(JsonNode node) {
        String errCode = ThirdPartyHttpSupport.text(node, "errcode");
        String errMsg = ThirdPartyHttpSupport.text(node, "errmsg");
        return (errCode == null ? "" : "errcode=" + errCode) + (errMsg == null ? "" : " " + errMsg);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String orDefault(String value, String fallback) {
        return notBlank(value) ? value : fallback;
    }

    /** 内存缓存的 access_token。 */
    private record CachedToken(String value, long expireAtMillis) {}
}
