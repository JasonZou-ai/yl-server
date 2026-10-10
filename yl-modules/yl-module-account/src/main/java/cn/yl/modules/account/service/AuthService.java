package cn.yl.modules.account.service;

import cn.yl.api.security.JwtTokenProvider;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.common.security.RefreshTokenStore;
import cn.yl.common.security.TokenBlacklistStore;
import cn.yl.common.sms.SmsScene;
import cn.yl.common.sms.SmsTicket;
import cn.yl.common.sms.SmsTicketStore;
import cn.yl.modules.account.config.ChannelDowngradeSwitch;
import cn.yl.modules.account.domain.LoginChannel;
import cn.yl.modules.account.domain.entity.LoginLog;
import cn.yl.modules.account.domain.entity.SysUser;
import cn.yl.modules.account.domain.thirdparty.LoginChannelAdapter;
import cn.yl.modules.account.dto.AccountProfile;
import cn.yl.modules.account.dto.LoginRequest;
import cn.yl.modules.account.dto.LoginResponse;
import cn.yl.modules.account.mapper.LoginLogMapper;
import cn.yl.modules.account.mapper.SysUserMapper;
import cn.yl.modules.account.security.SecondVerifyGuard;
import cn.yl.modules.account.security.SecondVerifyVerifier;
import cn.yl.modules.account.service.thirdparty.LoginChannelRegistry;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 认证服务（B2 / rpW1xZ，设计 §6）。
 *
 * <p>统一入口：各渠道适配平台身份 → 校验账号状态 → 聚合 {@link AccountProfile} → 换发本服务 access+refresh 双令牌 （refresh 存
 * Redis，可吊销）→ 写 {@code login_log}（成功/失败均留痕，失败原因脱敏为枚举文案）。
 *
 * <p>不标注事务：失败日志需在抛异常前落库，故各 mapper 调用各自提交。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    private static final String TOKEN_TYPE = "Bearer";

    private final SysUserMapper userMapper;
    private final LoginLogMapper loginLogMapper;
    private final AccountAggregateService accountAggregateService;
    private final JwtTokenProvider tokenProvider;
    private final RefreshTokenStore refreshTokenStore;
    private final TokenBlacklistStore blacklistStore;
    private final SecondVerifyGuard secondVerifyGuard;
    private final SecondVerifyVerifier secondVerifyVerifier;
    private final PasswordEncoder passwordEncoder;
    private final LoginChannelRegistry loginChannelRegistry;
    private final ChannelDowngradeSwitch channelDowngradeSwitch;
    private final SmsTicketStore smsTicketStore;

    /**
     * 登录（四渠道统一主流程）。
     *
     * <p>流程：按渠道取适配器（未接入 / 未完成平台配置 → 明确拒绝）→ 适配器解析登录名（平台渠道在此完成换证与绑定解析） → 账密渠道比对口令散列 → 校验账号状态 → 换发双令牌
     * → 写 {@code login_log}（成功/失败均留痕）。
     *
     * <p><b>开发期渠道降级（CR-M2-004）</b>：当 {@link ChannelDowngradeSwitch}
     * 开启、渠道<b>非账密渠道</b>且请求<b>携带口令</b>时，
     * 跳过平台适配器、改走账密校验通道（小程序端在平台授权渠道接入前用此通道联调）。<b>降级仍强制比对口令</b>，不是免密通道。
     *
     * @param channel 登录渠道
     * @param request 登录请求
     * @param ip 客户端 IP（写 login_log）
     * @param ua 客户端 UA（写 login_log）
     * @return 统一换发的令牌与档案摘要
     */
    public LoginResponse login(LoginChannel channel, LoginRequest request, String ip, String ua) {
        boolean downgrade = shouldDowngrade(channel, request);
        String username;
        if (downgrade) {
            username = resolveDowngradeUsername(request, channel, ip, ua);
            // 降级留痕：login_log 保持渠道原值（WXMP/DYMP），此处以应用日志补充「本次为账密回退」事实
            log.warn(
                    "开发期渠道降级登录：channel={} username={}（平台适配器未接入，已强制 BCrypt 口令校验）",
                    channel.code(),
                    username);
        } else {
            LoginChannelAdapter adapter;
            try {
                adapter = loginChannelRegistry.require(channel);
            } catch (BizException e) {
                writeLoginLog(null, null, channel, ip, ua, false, e.getMessage());
                throw e;
            }
            try {
                username = adapter.resolveUsername(request);
            } catch (BizException e) {
                // 平台渠道的换证/绑定失败：平台标识不进日志，仅落业务文案
                writeLoginLog(null, null, channel, ip, ua, false, e.getMessage());
                throw e;
            }
        }
        Optional<SysUser> found = userMapper.selectByUsername(username);
        if (found.isEmpty()) {
            writeLoginLog(null, username, channel, ip, ua, false, "账号不存在");
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        SysUser user = found.get();
        // 口令比对口径：账密渠道恒执行；平台渠道仅在降级时执行。
        // 降级成立的前提就是「携口令」（见 shouldDowngrade），故此处不存在"平台渠道跳过口令"的分支。
        if ((channel.passwordSupported() || downgrade)
                && !passwordMatched(user, request.getPassword())) {
            writeLoginLog(user.getId(), user.getUsername(), channel, ip, ua, false, "口令错误");
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            writeLoginLog(user.getId(), user.getUsername(), channel, ip, ua, false, "账号状态异常");
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        AccountProfile profile = accountAggregateService.loadProfile(user.getId());
        if (profile == null) {
            writeLoginLog(user.getId(), user.getUsername(), channel, ip, ua, false, "账号档案缺失");
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        LoginResponse response = issueTokens(profile);
        response.setChannelDowngrade(downgrade);
        touchLastLogin(user.getId());
        writeLoginLog(user.getId(), user.getUsername(), channel, ip, ua, true, null);
        return response;
    }

    /**
     * SMS-04：以短信核身 ticket 兑换登录态（P2-8-A，设计 §5）。
     *
     * <p>流程：{@code GETDEL} 消费 ticket（缺失/过期/重放 → {@code SMS_TICKET_INVALID}）→ 按 {@code phone_hash} 查
     * {@code sys_user}（<b>未命中 → {@code SMS_ACCOUNT_NOT_BOUND}，绝不自动建号</b>，见设计偏离 4 / 残留 R1） → 复用
     * {@link #issueTokens} 签发双令牌 → 写 {@code login_log}（{@code login_type=SMS}， {@code device_type}
     * 由 scene 映射）→ {@code touchLastLogin}。
     *
     * @param ticket 一次性 ticket
     * @param scene 端侧场景（用于 {@code device_type} 映射；缺省或非法时回落 {@code WEB}）
     * @param ip 客户端 IP
     * @param ua 客户端 UA
     */
    public LoginResponse loginByTicket(String ticket, String scene, String ip, String ua) {
        SmsTicket payload =
                smsTicketStore
                        .consume(ticket)
                        .orElseThrow(() -> new BizException(ErrorCode.SMS_TICKET_INVALID));
        Optional<SysUser> found = userMapper.selectByPhoneHash(payload.phoneHash());
        if (found.isEmpty()) {
            writeSmsLoginLog(null, scene, ip, ua, false, "手机号未绑定账号");
            throw new BizException(ErrorCode.SMS_ACCOUNT_NOT_BOUND);
        }
        SysUser user = found.get();
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            writeSmsLoginLog(user.getId(), scene, ip, ua, false, "账号状态异常");
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        AccountProfile profile = accountAggregateService.loadProfile(user.getId());
        if (profile == null) {
            writeSmsLoginLog(user.getId(), scene, ip, ua, false, "账号档案缺失");
            throw new BizException(ErrorCode.SMS_ACCOUNT_NOT_BOUND);
        }
        LoginResponse response = issueTokens(profile);
        touchLastLogin(user.getId());
        writeSmsLoginLog(user.getId(), scene, ip, ua, true, null);
        return response;
    }

    /**
     * 是否走开发期渠道降级（CR-M2-004）。
     *
     * <p>三个条件同时成立才降级，缺一即回落原路径（回到「渠道未配置 → 明确拒绝」）：
     *
     * <ol>
     *   <li>开关开启（默认 false，仅 dev profile 覆盖为 true）；
     *   <li>渠道<b>不是</b>账密渠道（账密渠道本就比对口令，无需降级）；
     *   <li>请求<b>携带非空口令</b> —— 这条同时是"降级不免密"的形式保证：能降级就必然有口令可比。
     * </ol>
     */
    private boolean shouldDowngrade(LoginChannel channel, LoginRequest request) {
        // allows() 内含两重判定：开关开启 + 渠道在白名单（仅 WECHAT/DOUYIN，均为非账密渠道）。
        // 白名单而非「非账密渠道」泛化，是为了把 FACE 这类「合规前置未办结而有意不接入」的渠道挡在外面。
        if (!channelDowngradeSwitch.allows(channel)) {
            return false;
        }
        String password = request.getPassword();
        return password != null && !password.isBlank();
    }

    /** 降级通道的登录名校验（与 {@code PasswordChannelAdapter} 同口径，不改动适配器契约）。 */
    private String resolveDowngradeUsername(
            LoginRequest request, LoginChannel channel, String ip, String ua) {
        String username = request.getUsername();
        if (username == null || username.isBlank()) {
            writeLoginLog(null, null, channel, ip, ua, false, "渠道降级登录缺少登录名");
            throw new BizException(ErrorCode.PARAM_INVALID, "渠道降级登录须提供登录名");
        }
        return username.trim();
    }

    /** 口令比对（抗时序比较由 BCrypt 保证）；口令散列缺失或未传口令一律视为不匹配。 */
    private boolean passwordMatched(SysUser user, String rawPassword) {
        if (rawPassword == null || user.getPasswordHash() == null) {
            return false;
        }
        return passwordEncoder.matches(rawPassword, user.getPasswordHash());
    }

    /**
     * 刷新 access 令牌。
     *
     * @param refreshToken 刷新令牌
     * @return 新的 access 令牌（refresh 保持不变）
     */
    public LoginResponse refresh(String refreshToken) {
        Long userId = refreshTokenStore.findUserId(refreshToken);
        if (userId == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        try {
            Claims claims = tokenProvider.parse(refreshToken);
            String type = claims.get(JwtTokenProvider.CLAIM_TYPE, String.class);
            if (!JwtTokenProvider.TYPE_REFRESH.equals(type)) {
                throw new BizException(ErrorCode.UNAUTHORIZED);
            }
        } catch (JwtException | IllegalArgumentException e) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        AccountProfile profile = accountAggregateService.loadProfile(userId);
        if (profile == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        String accessToken = issueAccessToken(profile);
        return assemble(accessToken, refreshToken, profile);
    }

    /**
     * 退出登录（D-4）：access 入黑名单 + （可选）refresh 吊销，实现<b>双令牌同时失效</b>。
     *
     * <p>① access：以 {@code sha256} 记入黑名单，TTL = {@link
     * JwtTokenProvider#remainingTtl(String)}（令牌剩余有效期）；已过期令牌无需登记。 ② refresh：携带非空 {@code
     * refreshToken} 时 {@link RefreshTokenStore#revoke}（键 {@code yl:rt:}）。
     *
     * <p>本方法不抛业务异常：登出为幂等出口，重复登出/令牌已失效均视为成功。请求体与登录态的身份由控制器提供（本层不再取身份）。
     *
     * @param accessToken 当前 access 令牌（可空）
     * @param refreshToken 待吊销的 refresh 令牌（可空）
     * @param userId 当前登录用户（黑名单值，便于审计）
     */
    public void logout(String accessToken, String refreshToken, long userId) {
        if (accessToken != null && !accessToken.isBlank()) {
            Duration remaining = tokenProvider.remainingTtl(accessToken);
            if (!remaining.isZero() && !remaining.isNegative()) {
                blacklistStore.add(accessToken, userId, remaining.toSeconds());
            }
        }
        if (refreshToken != null && !refreshToken.isBlank()) {
            refreshTokenStore.revoke(refreshToken);
        }
    }

    /**
     * 校验二次验证输入并签发一次性凭据。
     *
     * @param userId 当前用户
     * @param method 验证方式（SCAN/PHONE/FACE）
     * @param credential 渠道凭据
     * @return 一次性凭据串
     */
    public String issueSecondVerifyToken(long userId, String method, String credential) {
        if (!secondVerifyVerifier.verify(userId, method, credential)) {
            throw new BizException(ErrorCode.SECOND_VERIFY_REQUIRED, "二次验证未通过");
        }
        return secondVerifyGuard.issue(userId);
    }

    private LoginResponse issueTokens(AccountProfile profile) {
        String accessToken = issueAccessToken(profile);
        String refreshToken =
                tokenProvider.createRefreshToken(
                        profile.getUserId(), profile.getRoles(), profile.getOrgId());
        refreshTokenStore.save(
                refreshToken, profile.getUserId(), tokenProvider.getRefreshTtl().toSeconds());
        return assemble(accessToken, refreshToken, profile);
    }

    /**
     * 签发 access 令牌：把聚合档案中的<b>权限码 / 归属机构集合 / 数据域</b>一并签入（CR-M2-002 载荷补齐）。
     *
     * <p>这三项是第二闸（{@code @RequiresPermission} 读 perms）与第三闸（{@code DataScopePermissionHandler} 读
     * dataScope/orgIds）的唯一输入。此前只签 {@code uid/roles/orgId}，导致过滤器只能以"最严格默认值"装配（perms 空、dataScope=1），
     * 一旦端点启用权限码校验即全量 403，且机构数据域失效。
     */
    private String issueAccessToken(AccountProfile profile) {
        return tokenProvider.createAccessToken(
                profile.getUserId(),
                profile.getRoles(),
                profile.getOrgId(),
                profile.getPerms(),
                profile.getOrgIds(),
                profile.getDataScope());
    }

    private LoginResponse assemble(
            String accessToken, String refreshToken, AccountProfile profile) {
        LoginResponse response = new LoginResponse();
        response.setAccessToken(accessToken);
        response.setRefreshToken(refreshToken);
        response.setTokenType(TOKEN_TYPE);
        response.setExpiresIn(tokenProvider.getAccessTtl().toSeconds());
        response.setUserId(profile.getUserId());
        response.setRoles(profile.getRoles());
        response.setDataScope(profile.getDataScope());
        return response;
    }

    private void touchLastLogin(long userId) {
        SysUser update = new SysUser();
        update.setId(userId);
        update.setLastLoginAt(LocalDateTime.now());
        userMapper.updateById(update);
    }

    private void writeLoginLog(
            Long userId,
            String username,
            LoginChannel channel,
            String ip,
            String ua,
            boolean success,
            String failReason) {
        writeLoginLogRecord(
                userId,
                username,
                channel.loginType(),
                channel.deviceType(),
                ip,
                ua,
                success,
                failReason);
    }

    /** SMS 渠道留痕：{@code login_type=SMS}，{@code device_type} 由 scene 映射（兜底 WEB）。 */
    private void writeSmsLoginLog(
            Long userId, String scene, String ip, String ua, boolean success, String failReason) {
        writeLoginLogRecord(
                userId,
                null,
                LoginChannel.SMS.loginType(),
                SmsScene.deviceTypeOf(scene),
                ip,
                ua,
                success,
                failReason);
    }

    private void writeLoginLogRecord(
            Long userId,
            String username,
            String loginType,
            String deviceType,
            String ip,
            String ua,
            boolean success,
            String failReason) {
        try {
            LoginLog logRecord = new LoginLog();
            logRecord.setUserId(userId);
            logRecord.setUsername(username);
            logRecord.setLoginType(loginType);
            logRecord.setDeviceType(deviceType);
            logRecord.setIp(ip);
            logRecord.setUa(ua == null ? null : (ua.length() <= 255 ? ua : ua.substring(0, 255)));
            logRecord.setSuccess(success ? 1 : 0);
            logRecord.setFailReason(failReason);
            loginLogMapper.insert(logRecord);
        } catch (RuntimeException e) {
            // 登录日志为旁路留痕，写失败不应阻断认证主流程
            log.warn("写登录日志失败 userId={} loginType={}", userId, loginType, e);
        }
    }
}
