package cn.yl.api.security;

import java.util.Collections;
import java.util.List;

/**
 * 登录主体（B1-2，B2 扩展）。
 *
 * <p>不可变值对象：角色/权限/机构集合在构造时做快照、访问时返回不可变视图，避免外部持有的集合在鉴权期间被改写。
 *
 * @param userId 用户 ID
 * @param orgId 当前默认机构 ID（可空，监管类账号无机构归属）
 * @param roles 角色码集合（ELDER/ASSESSOR/FAMILY/ORG_ADMIN/SUPERVISOR）
 * @param perms 权限码集合（聚合去重，来自 sys_role_permission 种子）
 * @param orgIds 归属机构 ID 集合
 * @param dataScope 数据域口径（取用户跨角色最宽松值；1-本人 2-本机构 3-全量 4-只读全局）
 */
public record LoginUser(
        long userId,
        Long orgId,
        List<String> roles,
        List<String> perms,
        List<Long> orgIds,
        int dataScope) {

    /** 紧凑构造器：对角色/权限/机构集合做不可变拷贝，外部可变集合无法再影响鉴权判定。 */
    public LoginUser {
        roles = roles == null ? List.of() : List.copyOf(roles);
        perms = perms == null ? List.of() : List.copyOf(perms);
        orgIds = orgIds == null ? List.of() : List.copyOf(orgIds);
    }

    /**
     * 兼容 B1-2 现有调用（JwtAuthFilter）：仅注入角色，权限/机构域留待 rwkav6 鉴权层补全。
     *
     * <p>默认值采用最严格口径（data_scope=1 本人、无权限、无机构），避免未补全时越权放行。
     */
    public LoginUser(long userId, Long orgId, List<String> roles) {
        this(userId, orgId, roles, List.of(), List.of(), 1);
    }

    /** 角色码集合（不可变视图）。 */
    @Override
    public List<String> roles() {
        return Collections.unmodifiableList(roles);
    }

    /** 权限码集合（不可变视图）。 */
    public List<String> perms() {
        return Collections.unmodifiableList(perms);
    }

    /** 归属机构 ID 集合（不可变视图）。 */
    public List<Long> orgIds() {
        return Collections.unmodifiableList(orgIds);
    }

    /** 是否拥有任一角色。 */
    public boolean hasAnyRole(String... targets) {
        if (roles.isEmpty()) {
            return false;
        }
        for (String target : targets) {
            if (roles.contains(target)) {
                return true;
            }
        }
        return false;
    }

    /** 是否拥有指定权限码（来自 perms 聚合）。 */
    public boolean hasPermission(String perm) {
        return perms.contains(perm);
    }

    /** 当前默认机构 ID 的类型安全取值。 */
    public long requireOrgId() {
        if (orgId == null) {
            throw new IllegalStateException("当前账号无机构归属，无法执行机构级操作");
        }
        return orgId;
    }
}
