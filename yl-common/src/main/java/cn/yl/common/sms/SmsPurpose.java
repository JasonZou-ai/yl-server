package cn.yl.common.sms;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import java.util.Locale;

/**
 * 短信验证码用途（P2-8-A，设计 §4.1）。
 *
 * <p>用途参与 Redis 键构造（{@code yl:sms:code:{purpose}:{phoneHash}}），故不同用途的验证码互不干扰、互不核销。
 */
public enum SmsPurpose {
    /** 登录核身。 */
    LOGIN,
    /** 绑定手机号。 */
    BIND,
    /** 更换手机号。 */
    CHANGE_PHONE;

    /** 解析用途（大小写不敏感）；缺失/非法抛参数错误。 */
    public static SmsPurpose of(String purpose) {
        if (purpose == null || purpose.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "缺少验证码用途");
        }
        String normalized = purpose.trim().toUpperCase(Locale.ROOT);
        for (SmsPurpose candidate : values()) {
            if (candidate.name().equals(normalized)) {
                return candidate;
            }
        }
        throw new BizException(ErrorCode.PARAM_INVALID, "不支持的验证码用途：" + purpose);
    }
}
