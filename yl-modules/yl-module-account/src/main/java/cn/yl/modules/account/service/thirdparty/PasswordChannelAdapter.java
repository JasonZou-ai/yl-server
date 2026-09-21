package cn.yl.modules.account.service.thirdparty;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.account.domain.LoginChannel;
import cn.yl.modules.account.domain.thirdparty.LoginChannelAdapter;
import cn.yl.modules.account.dto.LoginRequest;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 账密渠道适配器（IOS / ANDROID，B2 / rpW1xZ）。
 *
 * <p>只做入参校验与登录名回显：口令散列属账号主数据，比对由 {@code AuthService} 统一完成，避免适配器反向依赖用户表。
 */
@Component
public class PasswordChannelAdapter implements LoginChannelAdapter {

    private static final Set<LoginChannel> CHANNELS =
            Set.of(LoginChannel.IOS, LoginChannel.ANDROID);

    @Override
    public Set<LoginChannel> channels() {
        return CHANNELS;
    }

    @Override
    public boolean configured() {
        return true;
    }

    @Override
    public String resolveUsername(LoginRequest request) {
        String username = request.getUsername();
        if (username == null || username.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "账密登录须提供登录名");
        }
        String password = request.getPassword();
        if (password == null || password.isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "账密登录须提供口令");
        }
        return username.trim();
    }
}
