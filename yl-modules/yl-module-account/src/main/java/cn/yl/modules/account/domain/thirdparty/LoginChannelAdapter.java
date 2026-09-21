package cn.yl.modules.account.domain.thirdparty;

import cn.yl.modules.account.domain.LoginChannel;
import cn.yl.modules.account.dto.LoginRequest;
import java.util.Set;

/**
 * 登录渠道适配器（B2 / rpW1xZ 四端统一入口的策略契约）。
 *
 * <p>{@code AuthService} 不再按渠道硬编码分支，而是按渠道取适配器：账密渠道（IOS/ANDROID）与平台授权渠道
 * （WECHAT/DOUYIN/FACE）走同一条主流程，差异收敛在适配器内——对应设计 §6「四渠道适配后统一换发本服务令牌」。
 */
public interface LoginChannelAdapter {

    /** 本适配器负责的渠道（账密适配器同时负责 IOS/ANDROID）。 */
    Set<LoginChannel> channels();

    /** 渠道是否可用（账密渠道恒为 true；平台授权渠道取决于平台配置是否齐备）。 */
    boolean configured();

    /**
     * 解析出本服务登录名。
     *
     * <p>平台授权渠道会在此完成「平台换证 + 绑定解析」，即返回后登录名即为可信身份；账密渠道仅回显并校验入参登录名， 口令比对仍由 {@code AuthService}
     * 负责（口令散列属账号主数据，不放入适配器）。
     *
     * @param request 登录请求
     * @return 本服务登录名
     */
    String resolveUsername(LoginRequest request);
}
