package cn.yl.modules.account.service.thirdparty;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.account.domain.LoginChannel;
import cn.yl.modules.account.domain.thirdparty.LoginChannelAdapter;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * 登录渠道适配器注册表（B2 / rpW1xZ）。
 *
 * <p>按渠道分发到唯一适配器。渠道无适配器、或适配器未完成平台配置时**明确拒绝**——安全默认：未接入/未配置的渠道
 * 不会静默降级为其它认证方式，也不会出现「只要请求带上渠道名就放行」的绕过面。
 *
 * <p>启动期校验「同一渠道不得重复注册」，把配置错误暴露在启动阶段而非首次登录时。
 *
 * <p>声明为 {@code final}：构造器会因重复注册抛异常，依据 SpotBugs {@code CT_CONSTRUCTOR_THROW} 的处置口径 （同 {@code
 * JwtTokenProvider}），以 final 类排除子类化后的终结器攻击面。
 */
@Component
public final class LoginChannelRegistry {

    private final Map<LoginChannel, LoginChannelAdapter> adapters =
            new EnumMap<>(LoginChannel.class);

    public LoginChannelRegistry(List<LoginChannelAdapter> candidates) {
        for (LoginChannelAdapter candidate : candidates) {
            for (LoginChannel channel : candidate.channels()) {
                LoginChannelAdapter previous = adapters.put(channel, candidate);
                if (previous != null) {
                    throw new IllegalStateException(
                            "登录渠道适配器重复注册："
                                    + channel
                                    + " -> "
                                    + previous.getClass().getName()
                                    + " / "
                                    + candidate.getClass().getName());
                }
            }
        }
    }

    /**
     * 取渠道适配器。
     *
     * @param channel 登录渠道
     * @return 已确认可用的适配器
     */
    public LoginChannelAdapter require(LoginChannel channel) {
        LoginChannelAdapter adapter = adapters.get(channel);
        if (adapter == null) {
            throw new BizException(ErrorCode.LOGIN_FAILED, "该渠道登录暂未接入：" + channel.code());
        }
        if (!adapter.configured()) {
            throw new BizException(ErrorCode.LOGIN_FAILED, "该渠道未完成平台配置，暂不可用：" + channel.code());
        }
        return adapter;
    }

    /** 当前已注册的渠道（含未配置者），用于启动自检与联调排障。 */
    public Set<LoginChannel> registeredChannels() {
        return Set.copyOf(adapters.keySet());
    }

    /** 当前已配置齐备、可对外提供的渠道。 */
    public Set<LoginChannel> availableChannels() {
        Set<LoginChannel> available = new TreeSet<>();
        adapters.forEach(
                (channel, adapter) -> {
                    if (adapter.configured()) {
                        available.add(channel);
                    }
                });
        return available;
    }
}
