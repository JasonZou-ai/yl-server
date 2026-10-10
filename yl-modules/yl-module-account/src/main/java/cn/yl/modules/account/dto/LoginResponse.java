package cn.yl.modules.account.dto;

import java.util.Collections;
import java.util.List;
import lombok.Data;

/** 登录/刷新响应：统一换发的双令牌 + 账号档案摘要（四端一致）。 */
@Data
public class LoginResponse {

    private String accessToken;
    private String refreshToken;

    /** 令牌类型，固定 Bearer。 */
    private String tokenType;

    /** access 有效期（秒）。 */
    private long expiresIn;

    private long userId;

    /** 角色码集合：写入即快照、读取返回不可变视图（见下方访问器）。 */
    private List<String> roles;

    /** 数据域口径（1-本人 2-本机构 3-全量 4-只读全局）。 */
    private int dataScope;

    /**
     * 本次登录是否经「开发期渠道降级」通道（CR-M2-004）。
     *
     * <p>{@code true} 表示：请求来自未接入平台适配器的渠道（WECHAT/DOUYIN），在 dev 开关开启且携带口令的前提下， 以<b>账密方式</b>完成了认证（已强制
     * BCrypt 比对，非免密）。生产环境该字段恒为 {@code false}。
     *
     * <p>对端侧的意义：端上可据此提示"当前为联调通道"，并<b>不得</b>据此放松任何本地校验。
     */
    private boolean channelDowngrade;

    /** 角色码集合（不可变视图；未赋值时为空集合）。 */
    public List<String> getRoles() {
        return roles == null ? List.of() : Collections.unmodifiableList(roles);
    }

    /** 写入时快照。 */
    public void setRoles(List<String> roles) {
        this.roles = roles == null ? List.of() : List.copyOf(roles);
    }
}
