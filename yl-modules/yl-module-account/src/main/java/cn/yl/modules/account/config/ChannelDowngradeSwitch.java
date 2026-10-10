package cn.yl.modules.account.config;

import cn.yl.modules.account.domain.LoginChannel;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 开发期渠道降级开关（CR-M2-004）。
 *
 * <p><b>为什么需要它</b>：微信端（WECHAT）与抖音端（DOUYIN）的平台授权渠道尚未接入适配器（{@code
 * WechatThirdPartyAuthenticator#configured()} 恒为 false），端侧因此<b>无法登录</b>，而「其余端切真实接口」的验收要求逐端跑通主链。
 * 联调期需要一个<b>不改动生产语义</b>的通道：让小程序端以账密方式登录， 使端侧主链与后端数据域得以端到端验证。
 *
 * <p><b>安全口径（四条红线，改动前必须读完）</b>：
 *
 * <ol>
 *   <li><b>默认关闭</b>：{@code yl.auth.channel-downgrade.enabled} 默认 {@code false}，且<b>只有 dev
 *       profile</b> 把它覆盖为 {@code true}。生产配置不写或写 false 即彻底失效，无「忘了关」的余地。
 *   <li><b>降级不等于免密</b>：降级分支<b>仍然强制 BCrypt 比对口令</b>（由 {@code AuthService} 统一执行）。绝不因为「平台渠道
 *       passwordSupported=false」而跳过口令校验 —— 这正是本开关最大的坑，见 {@code AuthServiceChannelDowngradeTest}
 *       各格单测。
 *   <li><b>必须携口令</b>：未传口令的请求<b>不触发</b>降级，仍走原路径（渠道未配置 → 明确拒绝），因此不存在「凭渠道名即放行」的绕过面。
 *   <li><b>渠道白名单，不得按「非账密渠道」泛化</b>：仅 {@link #ALLOWED_CHANNELS} 内的渠道可降级。**FACE 必须排除** ——
 *       人脸渠道的不接入是<b>合规前置未办结</b>（DPO 单独同意版本、PIA 增补、服务商选定三项齐备才可打开），若按「passwordSupported=false
 *       即降级」泛化，会把一个<b>有意不接入</b>的渠道顺手打通，等于绕过 DPO 会签。本项由 2026-09-23 四渠道对照验证实测发现并收窄。
 * </ol>
 *
 * <p>单独成组件而非在 {@code AuthService} 里散写 {@code @Value}：便于单测直接构造开关两态，无需启动 Spring 上下文。
 */
@Component
public final class ChannelDowngradeSwitch {

    /**
     * 允许降级的渠道白名单：**仅小程序端**。
     *
     * <p>FACE 的缺席是刻意的，不是遗漏 —— 见类注释红线 4。
     */
    private static final Set<LoginChannel> ALLOWED_CHANNELS =
            Set.of(LoginChannel.WECHAT, LoginChannel.DOUYIN);

    private final boolean enabled;

    /**
     * @param enabled 是否开启开发期渠道降级（默认 false，仅 dev profile 覆盖）
     */
    public ChannelDowngradeSwitch(
            @Value("${yl.auth.channel-downgrade.enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    /** 开关是否开启（不含渠道判定）。 */
    public boolean enabled() {
        return enabled;
    }

    /**
     * 该渠道是否<b>允许</b>走降级通道：开关开启、且在 {@link #ALLOWED_CHANNELS} 白名单内。
     *
     * <p>口令是否携带由 {@code AuthService} 另行判定（口令属请求数据，不属开关职责）。
     *
     * @param channel 登录渠道
     * @return 是否允许降级
     */
    public boolean allows(LoginChannel channel) {
        return enabled && ALLOWED_CHANNELS.contains(channel);
    }
}
