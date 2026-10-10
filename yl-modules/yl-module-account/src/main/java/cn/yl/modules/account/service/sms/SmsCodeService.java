package cn.yl.modules.account.service.sms;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.common.ratelimit.SlidingWindowCounter;
import cn.yl.common.security.MaskUtils;
import cn.yl.common.security.SensitiveFieldCodec;
import cn.yl.common.sms.RenderedCaptcha;
import cn.yl.common.sms.SmsCaptchaRenderer;
import cn.yl.common.sms.SmsCaptchaStore;
import cn.yl.common.sms.SmsCodeStore;
import cn.yl.common.sms.SmsPurpose;
import cn.yl.common.sms.SmsScene;
import cn.yl.common.sms.SmsSender;
import cn.yl.common.sms.SmsTicketStore;
import cn.yl.modules.account.config.SmsProperties;
import cn.yl.modules.account.dto.SmsCaptchaResponse;
import cn.yl.modules.account.dto.SmsSendRequest;
import cn.yl.modules.account.dto.SmsSendResponse;
import cn.yl.modules.account.dto.SmsTicketResponse;
import cn.yl.modules.account.dto.SmsVerifyRequest;
import cn.yl.modules.account.service.AuditService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * 短信核身编排服务（P2-8-A，设计 §5 行为规约）。
 *
 * <p>职责：SMS-01 图形验证码 → SMS-02 发送（频控 / 锁定 / 图形验证前置 / fail-closed）→ SMS-03 校验 （Lua 原子一次性核销 + 失败不退 +
 * 失败锁定）。
 *
 * <p><b>防手机号存在性枚举</b>（红线 §4）：SMS-02 全流程<b>不查库</b>，仅以手机号摘要 / IP / 指纹构造键， 对已注册与未注册手机号返回完全一致。
 *
 * <p><b>零新基建</b>：频控复用既有 {@link SlidingWindowCounter}（Lua 原子滑动窗口）；验证码 / 锁定 / 图形验证码 均以 Redis TTL
 * 承载，零新表、零调度。
 */
@Slf4j
@Service
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification = "配置对象与各端口实现均为 Spring 容器管理的单例，构造器注入是标准 DI 用法"))
public class SmsCodeService {

    private static final Pattern PHONE_PATTERN = Pattern.compile("^1[3-9]\\d{9}$");

    private static final long WINDOW_60S = 60L;
    private static final long WINDOW_1H = 3600L;
    private static final long WINDOW_1D = 86400L;

    private static final int DIGIT_BOUND = 10;
    private static final int DEFAULT_CODE_LENGTH = 6;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SmsProperties properties;
    private final ObjectProvider<SensitiveFieldCodec> codecProvider;
    private final SlidingWindowCounter counter;
    private final SmsCodeStore codeStore;
    private final SmsCaptchaStore captchaStore;
    private final SmsTicketStore ticketStore;
    private final SmsSender sender;
    private final SmsCaptchaRenderer captchaRenderer;
    private final AuditService auditService;

    /**
     * SMS-01：生成图形验证码（答案入 {@link SmsCaptchaStore}，TTL 120s）。
     *
     * @param scene 图形验证码用途场景标签（仅日志）
     */
    public SmsCaptchaResponse generateCaptcha(String scene) {
        RenderedCaptcha rendered = captchaRenderer.render();
        String captchaId = UUID.randomUUID().toString();
        long ttl = properties.getCaptcha().getTtlSeconds();
        captchaStore.save(captchaId, rendered.code(), ttl);
        log.debug("生成图形验证码：scene={} captchaId={}", scene, captchaId);
        return new SmsCaptchaResponse(captchaId, rendered.dataUri(), ttl);
    }

    /**
     * SMS-02：发送短信验证码（顺序即安全性，设计 §5）。
     *
     * @param request 发送请求
     * @param ip 客户端 IP（频控维度，不查库）
     * @param userAgent 客户端 UA（设备指纹的近似来源，A 段端侧未改，故以 UA 兜底）
     */
    public SmsSendResponse send(SmsSendRequest request, String ip, String userAgent) {
        if (!properties.isEnabled()) {
            // 应急停发开关（合规清单 F4）：fail-closed，绝不静默成功
            throw new BizException(ErrorCode.SMS_SEND_FAILED, "短信通道已停用");
        }
        String phone = requireValidPhone(request.getPhone());
        SmsPurpose purpose = SmsPurpose.of(request.getPurpose());
        SmsScene scene = SmsScene.of(request.getScene());
        String phoneHash = hashPhone(phone);
        String fingerprint = fingerprint(ip, userAgent);

        enforceRateLimits(phoneHash, ip, fingerprint);
        enforceLocked(phoneHash, purpose);
        enforceCaptcha(phoneHash, ip, request);

        String code = randomCode(properties.getCode().getLength());
        codeStore.save(phoneHash, purpose, code, properties.getCode().getTtlSeconds());
        // 通道不可用 → 抛 SMS_SEND_FAILED（fail-closed，可观测）
        sender.send(phone, purpose, code);
        log.info(
                "短信验证码已下发：phone={} purpose={} scene={}",
                MaskUtils.maskPhone(phone),
                purpose,
                scene);
        recordAudit("SMS_SEND", purpose, scene);
        return new SmsSendResponse(
                MaskUtils.maskPhone(phone), properties.getCode().getTtlSeconds());
    }

    /**
     * SMS-03：校验短信验证码（一次性核销 + 失败不退 + 失败锁定）。
     *
     * @param request 校验请求
     */
    public SmsTicketResponse verify(SmsVerifyRequest request) {
        String phone = requireValidPhone(request.getPhone());
        SmsPurpose purpose = SmsPurpose.of(request.getPurpose());
        SmsScene scene = SmsScene.of(request.getScene());
        String submittedCode = requireNonBlank(request.getCode(), "缺少验证码");
        String phoneHash = hashPhone(phone);

        enforceLocked(phoneHash, purpose);

        if (!codeStore.consumeIfMatches(phoneHash, purpose, submittedCode)) {
            long failures =
                    codeStore.incrementFailure(
                            phoneHash, purpose, properties.getLimits().getErrLockTtlSeconds());
            log.warn("短信验证码校验失败：purpose={} 累计失败={}", purpose, failures);
            // ★ 错误与过期同码（20006）：防攻击者以「错误 vs 过期」差异探测状态机
            throw new BizException(ErrorCode.SMS_CODE_INVALID);
        }
        codeStore.clearFailure(phoneHash, purpose);

        String ticket = UUID.randomUUID().toString().replace("-", "");
        long ticketTtl = properties.getTicket().getTtlSeconds();
        ticketStore.save(ticket, phoneHash, scene.name(), purpose, ticketTtl);
        recordAudit("SMS_VERIFY", purpose, scene);
        return new SmsTicketResponse(ticket, ticketTtl);
    }

    /**
     * 审计留痕（方案文档 §7.1：SMS-02/03/04 全量留痕）。
     *
     * <p><b>脱敏</b>：{@code audit_log} 的 IP / UA 由 {@link AuditService} 自动采集；{@code detail} 只写 {@code
     * purpose}/{@code scene} 两个枚举，**绝不写手机号或验证码**。
     *
     * <p><b>sensitive=false</b>：本端点无第四闸（二次验证）语义，置 {@code true} 会谎报「二次验证已通过」。
     *
     * <p><b>失败开放</b>：留痕是旁路能力，写失败只记警告，不得阻断核身主链路（同 {@code AuthService#writeLoginLog}）。
     */
    private void recordAudit(String action, SmsPurpose purpose, SmsScene scene) {
        try {
            auditService.record(
                    action,
                    "SMS",
                    false,
                    "{\"purpose\":\"" + purpose.name() + "\",\"scene\":\"" + scene.name() + "\"}");
        } catch (RuntimeException ex) {
            log.warn("短信审计留痕失败：action={}", action, ex);
        }
    }

    /** 5 条发送频控（§4.3）；超限短路 10429。全部不查库，防枚举。 */
    private void enforceRateLimits(String phoneHash, String ip, String fingerprint) {
        SmsProperties.Limits limits = properties.getLimits();
        checkLimit("sms:rl:ph:" + phoneHash + ":60s", WINDOW_60S, limits.getPhone60s());
        checkLimit("sms:rl:ph:" + phoneHash + ":1h", WINDOW_1H, limits.getPhone1h());
        checkLimit("sms:rl:ph:" + phoneHash + ":1d", WINDOW_1D, limits.getPhone1d());
        checkLimit("sms:rl:ip:" + ip + ":1h", WINDOW_1H, limits.getIp1h());
        checkLimit("sms:rl:dev:" + fingerprint + ":1d", WINDOW_1D, limits.getDevice1d());
    }

    private void checkLimit(String dimension, long windowSeconds, long limit) {
        if (limit <= 0) {
            return;
        }
        if (counter.recordAndCount(dimension, windowSeconds) > limit) {
            throw new BizException(ErrorCode.RATE_LIMITED);
        }
    }

    private void enforceLocked(String phoneHash, SmsPurpose purpose) {
        long threshold = properties.getLimits().getErrLock();
        if (threshold > 0 && codeStore.failureCount(phoneHash, purpose) >= threshold) {
            throw new BizException(ErrorCode.RATE_LIMITED);
        }
    }

    /** 图形验证触发判定（§4.3）：同手机号 1h≥阈值 或 同 IP 1h≥阈值。 */
    private void enforceCaptcha(String phoneHash, String ip, SmsSendRequest request) {
        if (!captchaRequired(phoneHash, ip)) {
            return;
        }
        if (isBlank(request.getCaptchaId()) || isBlank(request.getCaptchaCode())) {
            throw new BizException(ErrorCode.SMS_CAPTCHA_REQUIRED);
        }
        String answer = captchaStore.consume(request.getCaptchaId()).orElse(null);
        if (answer == null || !answer.equalsIgnoreCase(request.getCaptchaCode().trim())) {
            throw new BizException(ErrorCode.SMS_CAPTCHA_INVALID);
        }
    }

    private boolean captchaRequired(String phoneHash, String ip) {
        SmsProperties.Limits limits = properties.getLimits();
        long phoneThreshold = limits.getCaptchaPhone1h();
        long ipThreshold = limits.getCaptchaIp1h();
        boolean byPhone =
                phoneThreshold > 0
                        && counter.recordAndCount("sms:cap:ph:" + phoneHash + ":1h", WINDOW_1H)
                                >= phoneThreshold;
        boolean byIp =
                ipThreshold > 0
                        && counter.recordAndCount("sms:cap:ip:" + ip + ":1h", WINDOW_1H)
                                >= ipThreshold;
        return byPhone || byIp;
    }

    /** 手机号 → HMAC-SHA256 摘要；未配置摘要密钥时 fail-closed（绝不退化为明文键）。 */
    private String hashPhone(String phone) {
        SensitiveFieldCodec codec = codecProvider.getIfAvailable();
        if (codec == null) {
            throw new BizException(ErrorCode.SMS_SEND_FAILED, "未配置敏感字段摘要密钥，短信核身暂不可用");
        }
        return codec.hashForSearch(phone);
    }

    private String requireValidPhone(String raw) {
        if (raw == null || !PHONE_PATTERN.matcher(raw.trim()).matches()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "手机号格式不正确");
        }
        return raw.trim();
    }

    private String requireNonBlank(String value, String message) {
        if (isBlank(value)) {
            throw new BizException(ErrorCode.PARAM_INVALID, message);
        }
        return value.trim();
    }

    private String randomCode(int length) {
        int len = length > 0 ? length : DEFAULT_CODE_LENGTH;
        StringBuilder builder = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            builder.append(RANDOM.nextInt(DIGIT_BOUND));
        }
        return builder.toString();
    }

    /**
     * 设备指纹近似：A 段端侧未提供设备标识，优先用 UA 的稳定散列，缺省回退 IP。
     *
     * <p>语义局限（P2-8-B 待补）：UA 粒度粗于真实设备指纹，仅作 A 段频控兜底。
     */
    private String fingerprint(String ip, String userAgent) {
        if (!isBlank(userAgent)) {
            return Integer.toHexString(userAgent.hashCode());
        }
        return ip == null ? "unknown" : ip;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
