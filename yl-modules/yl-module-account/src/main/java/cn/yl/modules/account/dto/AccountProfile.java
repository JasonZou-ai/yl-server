package cn.yl.modules.account.dto;

import java.util.List;
import lombok.Data;

/**
 * 账号聚合档案（rwkav6 鉴权四道闸的统一输入）。
 *
 * <p>由 {@code AccountAggregateService} 按用户聚合：角色码、权限码（去重）、归属机构集合、默认机构、以及
 * 跨角色取「最宽松」的数据域口径。权限矩阵逐格可回溯：本对象的 perms 完全来自 sys_role_permission 种子。
 */
@Data
public class AccountProfile {

    private long userId;
    private Long orgId;
    private List<String> roles;
    private List<String> perms;
    private List<Long> orgIds;
    private int dataScope;
}
