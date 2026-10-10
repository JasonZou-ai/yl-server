package cn.yl.modules.account.domain;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import java.util.Locale;

/**
 * 登录渠道（PRD §8 统一原则 / 设计 §6）。对应 {@code POST /auth/login/{channel}} 的路径变量。
 *
 * <p>各渠道适配平台身份后，统一换发本服务双令牌，并写 {@code login_log}（login_type/device_type 取值对齐 schema 注释）。 MVP
 * 仅落账密渠道（IOS/ANDROID）；微信（WECHAT）/抖音（DOUYIN）平台授权与 FACE 人脸渠道预留。
 *
 * <p>{@link #SMS}（P2-8-A）：短信核身渠道，登录态由 {@code /api/v1/auth/login-by-ticket} 的 ticket 兑换签发， 不经 {@code
 * /auth/login/{channel}} 路径（该渠道不参与账密比对）。{@code device_type} 由 ticket 内 scene 决定， 此处的 {@code WEB}
 * 仅为缺省兜底。
 */
public enum LoginChannel {
    WECHAT("WECHAT", "WXMP", false),
    DOUYIN("DOUYIN", "DYMP", false),
    IOS("PWD", "IOS", true),
    ANDROID("PWD", "ANDROID", true),
    FACE("FACE", "IOS", false),
    SMS("SMS", "WEB", false);

    private final String loginType;
    private final String deviceType;
    private final boolean passwordSupported;

    LoginChannel(String loginType, String deviceType, boolean passwordSupported) {
        this.loginType = loginType;
        this.deviceType = deviceType;
        this.passwordSupported = passwordSupported;
    }

    /** 写入 login_log.login_type。 */
    public String loginType() {
        return loginType;
    }

    /** 写入 login_log.device_type。 */
    public String deviceType() {
        return deviceType;
    }

    /** 是否支持账密登录（MVP 仅 IOS/ANDROID）。 */
    public boolean passwordSupported() {
        return passwordSupported;
    }

    /** 渠道码（枚举名）。 */
    public String code() {
        return name();
    }

    /** 解析路径渠道（大小写不敏感）；非法渠道抛参数错误。 */
    public static LoginChannel of(String channel) {
        if (channel == null || channel.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少登录渠道");
        }
        String normalized = channel.trim().toUpperCase(Locale.ROOT);
        for (LoginChannel candidate : values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "不支持的登录渠道：" + channel);
    }
}
