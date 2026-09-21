package cn.yl.modules.account.service;

import cn.yl.api.security.JwtTokenProvider;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.common.security.RefreshTokenStore;
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
    private final SecondVerifyGuard secondVerifyGuard;
    private final SecondVerifyVerifier secondVerifyVerifier;
    private final PasswordEncoder passwordEncoder;
    private final LoginChannelRegistry loginChannelRegistry;

    /**
     * 登录（四渠道统一主流程）。
     *
     * <p>流程：按渠道取适配器（未接入 / 未完成平台配置 → 明确拒绝）→ 适配器解析登录名（平台渠道在此完成换证与绑定解析） → 账密渠道比对口令散列 → 校验账号状态 → 换发双令牌
     * → 写 {@code login_log}（成功/失败均留痕）。
     *
     * @param channel 登录渠道
     * @param request 登录请求
     * @param ip 客户端 IP（写 login_log）
     * @param ua 客户端 UA（写 login_log）
     * @return 统一换发的令牌与档案摘要
     */
    public LoginResponse login(LoginChannel channel, LoginRequest request, String ip, String ua) {
        LoginChannelAdapter adapter;
        try {
            adapter = loginChannelRegistry.require(channel);
        } catch (BizException e) {
            writeLoginLog(null, null, channel, ip, ua, false, e.getMessage());
            throw e;
        }
        String username;
        try {
            username = adapter.resolveUsername(request);
        } catch (BizException e) {
            // 平台渠道的换证/绑定失败：平台标识不进日志，仅落业务文案
            writeLoginLog(null, null, channel, ip, ua, false, e.getMessage());
            throw e;
        }
        Optional<SysUser> found = userMapper.selectByUsername(username);
        if (found.isEmpty()) {
            writeLoginLog(null, username, channel, ip, ua, false, "账号不存在");
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        SysUser user = found.get();
        if (channel.passwordSupported() && !passwordMatched(user, request.getPassword())) {
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
        touchLastLogin(user.getId());
        writeLoginLog(user.getId(), user.getUsername(), channel, ip, ua, true, null);
        return response;
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
        String accessToken =
                tokenProvider.createAccessToken(
                        profile.getUserId(), profile.getRoles(), profile.getOrgId());
        return assemble(accessToken, refreshToken, profile);
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
        String accessToken =
                tokenProvider.createAccessToken(
                        profile.getUserId(), profile.getRoles(), profile.getOrgId());
        String refreshToken =
                tokenProvider.createRefreshToken(
                        profile.getUserId(), profile.getRoles(), profile.getOrgId());
        refreshTokenStore.save(
                refreshToken, profile.getUserId(), tokenProvider.getRefreshTtl().toSeconds());
        return assemble(accessToken, refreshToken, profile);
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
        try {
            LoginLog logRecord = new LoginLog();
            logRecord.setUserId(userId);
            logRecord.setUsername(username);
            logRecord.setLoginType(channel.loginType());
            logRecord.setDeviceType(channel.deviceType());
            logRecord.setIp(ip);
            logRecord.setUa(ua == null ? null : (ua.length() <= 255 ? ua : ua.substring(0, 255)));
            logRecord.setSuccess(success ? 1 : 0);
            logRecord.setFailReason(failReason);
            loginLogMapper.insert(logRecord);
        } catch (RuntimeException e) {
            // 登录日志为旁路留痕，写失败不应阻断认证主流程
            log.warn("写登录日志失败 userId={} channel={}", userId, channel.code(), e);
        }
    }
}
