package cn.yl.common.sms;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import java.util.Locale;

/**
 * 短信核身场景（P2-8-A，设计 §4.1）——端侧来源，映射到 {@code login_log.device_type}。
 *
 * <p>取值与四端一一对应；映射口径与 {@code login_log.device_type} schema 注释（WXMP/DYMP/IOS/ANDROID/WEB）一致。
 */
public enum SmsScene {
    WECHAT("WXMP"),
    DOUYIN("DYMP"),
    IOS("IOS"),
    ANDROID("ANDROID");

    /** 无法识别场景时的兜底设备类型。 */
    public static final String FALLBACK_DEVICE_TYPE = "WEB";

    private final String deviceType;

    SmsScene(String deviceType) {
        this.deviceType = deviceType;
    }

    /** 映射到 {@code login_log.device_type}。 */
    public String deviceType() {
        return deviceType;
    }

    /** 解析场景（大小写不敏感）；缺失/非法抛参数错误。 */
    public static SmsScene of(String scene) {
        if (scene == null || scene.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少短信场景");
        }
        String normalized = scene.trim().toUpperCase(Locale.ROOT);
        for (SmsScene candidate : values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "不支持的短信场景：" + scene);
    }

    /**
     * 宽松映射：非法/缺失场景回落 {@link #FALLBACK_DEVICE_TYPE}，绝不抛错。
     *
     * <p>用于 SMS-04 兑换登录态时写 {@code login_log.device_type}——留痕不应因场景串异常而阻断登录主链路。
     */
    public static String deviceTypeOf(String scene) {
        if (scene == null || scene.isBlank()) {
            return FALLBACK_DEVICE_TYPE;
        }
        String normalized = scene.trim().toUpperCase(Locale.ROOT);
        for (SmsScene candidate : values()) {
            if (candidate.name().equals(normalized)) {
                return candidate.deviceType;
            }
        }
        return FALLBACK_DEVICE_TYPE;
    }
}
