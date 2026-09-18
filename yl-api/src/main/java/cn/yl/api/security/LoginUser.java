package cn.yl.api.security;

import java.util.Collections;
import java.util.List;

/**
 * 登录主体（B1-2）。
 *
 * <p>不可变值对象：角色集合在构造时做快照、访问时返回不可变视图，避免外部持有的集合在被鉴权期间被改写。
 *
 * @param userId 用户 ID
 * @param orgId 当前机构 ID（可空，监管类账号无机构归属）
 * @param roles 角色码集合（ELDER/ASSESSOR/FAMILY/ORG_ADMIN/SUPERVISOR）
 */
public record LoginUser(long userId, Long orgId, List<String> roles) {

    /** 紧凑构造器：对角色集合做不可变拷贝，外部可变集合无法再影响鉴权判定 */
    public LoginUser {
        roles = roles == null ? List.of() : List.copyOf(roles);
    }

    /**
     * 角色码集合（不可变视图）。
     *
     * <p>显式覆盖 record 自动生成的访问器，防止调用方通过返回值增删角色绕过鉴权。
     */
    @Override
    public List<String> roles() {
        return Collections.unmodifiableList(roles);
    }

    /** 是否拥有任一角色 */
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

    /** 当前机构 ID 的类型安全取值 */
    public long requireOrgId() {
        if (orgId == null) {
            throw new IllegalStateException("当前账号无机构归属，无法执行机构级操作");
        }
        return orgId;
    }
}
