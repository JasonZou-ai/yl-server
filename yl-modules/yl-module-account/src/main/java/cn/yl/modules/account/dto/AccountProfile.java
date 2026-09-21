package cn.yl.modules.account.dto;

import java.util.Collections;
import java.util.List;
import lombok.Data;

/**
 * 账号聚合档案（rwkav6 鉴权四道闸的统一输入）。
 *
 * <p>由 {@code AccountAggregateService} 按用户聚合：角色码、权限码（去重）、归属机构集合、默认机构、以及
 * 跨角色取「最宽松」的数据域口径。权限矩阵逐格可回溯：本对象的 perms 完全来自 sys_role_permission 种子。
 *
 * <p>集合字段按「写入即快照 + 读取返回不可变视图」处理（与 {@code PageResult} 同口径）：鉴权判定直接依赖这些集合， 若调用方持有可变引用即可在鉴权期间改写，故不做裸透传。
 */
@Data
public class AccountProfile {

    private long userId;
    private Long orgId;

    /** 角色码集合：写入即快照、读取返回不可变视图（见下方访问器）。 */
    private List<String> roles;

    /** 权限码集合：同上。 */
    private List<String> perms;

    /** 归属机构 ID 集合：同上。 */
    private List<Long> orgIds;

    private int dataScope;

    /** 角色码集合（不可变视图；未赋值时为空集合）。 */
    public List<String> getRoles() {
        return roles == null ? List.of() : Collections.unmodifiableList(roles);
    }

    /** 写入时快照。 */
    public void setRoles(List<String> roles) {
        this.roles = roles == null ? List.of() : List.copyOf(roles);
    }

    /** 权限码集合（不可变视图；未赋值时为空集合）。 */
    public List<String> getPerms() {
        return perms == null ? List.of() : Collections.unmodifiableList(perms);
    }

    /** 写入时快照。 */
    public void setPerms(List<String> perms) {
        this.perms = perms == null ? List.of() : List.copyOf(perms);
    }

    /** 归属机构集合（不可变视图；未赋值时为空集合）。 */
    public List<Long> getOrgIds() {
        return orgIds == null ? List.of() : Collections.unmodifiableList(orgIds);
    }

    /** 写入时快照。 */
    public void setOrgIds(List<Long> orgIds) {
        this.orgIds = orgIds == null ? List.of() : List.copyOf(orgIds);
    }
}
