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

    /** data_scope=1（本人）码值：与 account 模块 {@code DataScope.SELF} 同义，枚举不在本模块故以常量承载。 */
    private static final int SCOPE_SELF = 1;

    /** 紧凑构造器：对角色/权限/机构集合做不可变拷贝，外部可变集合无法再影响鉴权判定。 */
    public LoginUser {
        roles = roles == null ? List.of() : List.copyOf(roles);
        perms = perms == null ? List.of() : List.copyOf(perms);
        orgIds = orgIds == null ? List.of() : List.copyOf(orgIds);
    }

    /**
     * 兼容 B1-2 旧调用（仅注入角色）——<b>已弃用，禁止新增使用</b>。
     *
     * <p>默认值采用最严格口径（data_scope=1 本人、无权限、无机构），但这**不是安全，而是"假性收紧"**：权限码集合恒空会让任何
     * {@code @RequiresPermission} 端点的调用方得到全量 403，同时数据域恒为「仅本人」使评估员/机构管理员看不到本机构数据。CR-M2-002（缺口 B）已让
     * {@link JwtAuthFilter} 改用 6 参构造器并携带完整载荷，本构造器仅为历史调用保留，待全量核对后删除。
     *
     * @deprecated 请使用 {@code LoginUser(userId, orgId, roles, perms, orgIds,
     *     dataScope)}；权限与机构域必须来自服务端聚合档案。
     */
    @Deprecated(forRemoval = true)
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

    /**
     * 是否「本人域（data_scope=1）」视角 —— D4-R7 状态闸的判定入口。
     *
     * <p>为什么不判 {@code hasAnyRole("ELDER")}：角色码常量在 account 模块，业务模块引用会形成跨模块依赖； 且按 {@code
     * docs/change/D4-R7-elder-report-self-read.md} §8 实证，多角色账号取的是 <b>最宽值（max）</b>——持任一员工角色即被抬到
     * 2/4/5，故 <b>落 scope=1 者必为纯 ELDER 单角色账号</b>。 该不变式已由 {@code AccountAggregateServiceTest} 锁定，故
     * {@code dataScope == 1} 与「老人自读」等价且有契约保护。
     */
    public boolean isSelfScope() {
        return dataScope == SCOPE_SELF;
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
