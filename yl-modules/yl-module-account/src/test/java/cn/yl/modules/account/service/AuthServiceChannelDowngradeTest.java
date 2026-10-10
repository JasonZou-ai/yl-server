package cn.yl.modules.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yl.api.security.JwtTokenProvider;
import cn.yl.common.exception.BizException;
import cn.yl.common.security.RefreshTokenStore;
import cn.yl.common.security.TokenBlacklistStore;
import cn.yl.common.sms.SmsTicketStore;
import cn.yl.modules.account.config.ChannelDowngradeSwitch;
import cn.yl.modules.account.domain.LoginChannel;
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
import cn.yl.modules.account.service.thirdparty.PasswordChannelAdapter;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 开发期渠道降级单测（CR-M2-004）。
 *
 * <p><b>本测试守的是一条红线：降级 ≠ 免密。</b>平台渠道（WECHAT/DOUYIN）的 {@code passwordSupported()} 为 {@code
 * false}，原实现里的口令比对条件是 {@code channel.passwordSupported() && !passwordMatched(...)} ——
 * 若照抄该条件，降级路径就会<b>整段跳过口令校验</b>，变成"知道渠道名 + 任意口令即可登录"的越权面。
 *
 * <p>四格用例分别锁死：①开关关闭时行为与改造前完全一致；②未携口令不降级；③<b>口令错误必须被拒</b>（此格若代码跳过比对就会失败）；④口令正确才成功且带审计标记。
 *
 * <p>口令比对面用<b>真实 {@link BCryptPasswordEncoder}</b> 而非 mock —— 若 mock 掉 {@code PasswordEncoder}，
 * 这四格就变成自证同义反复，"跳过校验"的坑又会被放过（与 CR-M2-003 同类教训）。
 */
class AuthServiceChannelDowngradeTest {

    private static final String USERNAME = "assessor_dev";
    private static final String RAW_PASSWORD = "YlDev@2026";

    @Test
    @DisplayName("格1 开关关闭：WECHAT 携口令仍被拒（走原路径『渠道未配置』，行为与改造前一致）")
    void switchOffKeepsOriginalBehaviour() {
        Fixture f = new Fixture(false);

        assertThatThrownBy(
                        () ->
                                f.service.login(
                                        LoginChannel.WECHAT,
                                        request(RAW_PASSWORD),
                                        "127.0.0.1",
                                        "ua"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未完成平台配置");
    }

    @Test
    @DisplayName("格2 开关开启但未携口令：不触发降级，仍按渠道未配置拒绝（不存在『报渠道名即放行』）")
    void missingPasswordDoesNotDowngrade() {
        Fixture f = new Fixture(true);

        assertThatThrownBy(
                        () ->
                                f.service.login(
                                        LoginChannel.WECHAT, request(null), "127.0.0.1", "ua"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未完成平台配置");
    }

    @Test
    @DisplayName("格3 开关开启 + 口令错误：必须被拒 且 命中『口令错误』留痕（锁死『跳过口令校验』）")
    void wrongPasswordMustBeRejected() {
        Fixture f = new Fixture(true);

        assertThatThrownBy(
                        () ->
                                f.service.login(
                                        LoginChannel.WECHAT,
                                        request("wrong-password"),
                                        "127.0.0.1",
                                        "ua"))
                .isInstanceOf(BizException.class);

        // 决定性断言：失败原因必须是「口令错误」。若代码沿用 passwordSupported() 条件跳过比对，
        // 本格会成功签发令牌、根本走不到这条留痕 —— 测试即失败。
        verify(f.loginLogMapper).insert(argThat(log -> "口令错误".equals(log.getFailReason())));
    }

    @Test
    @DisplayName("格4 开关开启 + 口令正确：登录成功，且响应回 channelDowngrade=true")
    void correctPasswordSucceedsWithAuditFlag() {
        Fixture f = new Fixture(true);

        LoginResponse response =
                f.service.login(LoginChannel.WECHAT, request(RAW_PASSWORD), "127.0.0.1", "ua");

        assertThat(response.isChannelDowngrade()).isTrue();
        assertThat(response.getAccessToken()).isNotBlank();
        assertThat(response.getUserId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("补充：账密渠道（IOS）开关开启时不走降级，channelDowngrade 恒为 false")
    void passwordChannelIsNeverMarkedDowngraded() {
        Fixture f = new Fixture(true);

        LoginResponse response =
                f.service.login(LoginChannel.IOS, request(RAW_PASSWORD), "127.0.0.1", "ua");

        assertThat(response.isChannelDowngrade()).isFalse();
    }

    @Test
    @DisplayName("格6 FACE 与 WECHAT 同构场景对比：FACE 不在白名单，即使开关开启 + 口令正确仍被拒")
    void faceChannelIsExcludedByWhitelist() {
        Fixture f = new Fixture(true);

        // 同构条件（已注册但未配置 + 开关开启 + 口令正确）：WECHAT 会降级成功，FACE 必须仍被拒。
        // 若降级判定按「非账密渠道」泛化，本格就会失败 —— 即"有意不接入的 FACE 被顺手打通"。
        assertThatThrownBy(
                        () ->
                                f.service.login(
                                        LoginChannel.FACE,
                                        request(RAW_PASSWORD),
                                        "127.0.0.1",
                                        "ua"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("未完成平台配置");
    }

    private static LoginRequest request(String password) {
        LoginRequest request = new LoginRequest();
        request.setUsername(USERNAME);
        request.setPassword(password);
        return request;
    }

    /** 组装被测服务：真实注册表 + 真实 BCrypt，其余协作者以替身隔离。 */
    private static final class Fixture {

        private final AuthService service;
        private final LoginLogMapper loginLogMapper;

        private Fixture(boolean downgradeEnabled) {
            PasswordEncoder encoder = new BCryptPasswordEncoder();
            SysUser user = new SysUser();
            user.setId(1L);
            user.setUsername(USERNAME);
            user.setPasswordHash(encoder.encode(RAW_PASSWORD));
            user.setStatus(1);

            SysUserMapper userMapper = mock(SysUserMapper.class);
            when(userMapper.selectByUsername(anyString())).thenReturn(Optional.of(user));

            this.loginLogMapper = mock(LoginLogMapper.class);

            AccountProfile profile = new AccountProfile();
            profile.setUserId(1L);
            profile.setRoles(List.of("ASSESSOR"));
            AccountAggregateService aggregateService = mock(AccountAggregateService.class);
            when(aggregateService.loadProfile(anyLong())).thenReturn(profile);

            JwtTokenProvider tokenProvider = mock(JwtTokenProvider.class);
            when(tokenProvider.createAccessToken(
                            anyLong(), anyList(), any(), anyList(), anyList(), anyInt()))
                    .thenReturn("access-token");
            when(tokenProvider.createRefreshToken(anyLong(), anyList(), any()))
                    .thenReturn("refresh-token");
            when(tokenProvider.getAccessTtl()).thenReturn(Duration.ofHours(2));
            when(tokenProvider.getRefreshTtl()).thenReturn(Duration.ofDays(14));

            this.service =
                    new AuthService(
                            userMapper,
                            loginLogMapper,
                            aggregateService,
                            tokenProvider,
                            mock(RefreshTokenStore.class),
                            mock(TokenBlacklistStore.class),
                            mock(SecondVerifyGuard.class),
                            mock(SecondVerifyVerifier.class),
                            encoder,
                            // 真实注册表：WECHAT 适配器 未配置 → require() 明确拒绝；IOS 走真实账密适配器
                            new LoginChannelRegistry(
                                    List.of(
                                            new PasswordChannelAdapter(),
                                            new UnconfiguredPlatformAdapter(LoginChannel.WECHAT),
                                            new UnconfiguredPlatformAdapter(LoginChannel.FACE))),
                            new ChannelDowngradeSwitch(downgradeEnabled),
                            mock(SmsTicketStore.class));
        }
    }

    /** 模拟「平台适配器已注册但未完成平台配置」的渠道（与线上 WechatThirdPartyAuthenticator 现状一致）。 */
    private static final class UnconfiguredPlatformAdapter implements LoginChannelAdapter {

        private final LoginChannel channel;

        private UnconfiguredPlatformAdapter(LoginChannel channel) {
            this.channel = channel;
        }

        @Override
        public Set<LoginChannel> channels() {
            return Set.of(channel);
        }

        @Override
        public boolean configured() {
            return false;
        }

        @Override
        public String resolveUsername(LoginRequest request) {
            throw new IllegalStateException("未配置渠道不应被解析登录名");
        }
    }
}
