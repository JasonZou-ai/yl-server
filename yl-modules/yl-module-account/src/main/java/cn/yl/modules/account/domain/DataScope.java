package cn.yl.modules.account.domain;

/**
 * 数据域枚举（对应 sys_role.data_scope）。用于四道闸第三闸的数据权限隔离。
 *
 * <p><b>1-本人</b> / <b>2-本机构</b> / <b>3-全量</b> / <b>4-只读全局</b> / <b>5-关系级</b>。
 *
 * <p><b>★ 不要用数值大小推断可见范围（CR-M2-002 / D-1 裁决 2）</b>：加入 5-关系级后，数值序不再等价于宽严序 —— 5 只对「与自己存在有效亲情绑定的老人」开放，比
 * 2-本机构<b>更窄</b>。第三闸因此按<b>枚举值逐一分支</b>判定，禁止写成 {@code scope >= ALL.code()} 这类比较式（会把 5
 * 误判成"不限"，直接造成跨机构泄露）。
 *
 * <p>各档语义：
 *
 * <ul>
 *   <li>{@link #SELF}(1)：仅本人数据（按归属用户列过滤）；无"本人"列可表达的业务表<b>失败即拒</b>；
 *   <li>{@link #ORG}(2)：本机构数据（按 {@code org_id IN (我的机构)}）；
 *   <li>{@link #ALL}(3)：全量（不加限；本期五角色均不占用，留给平台级账号）；
 *   <li>{@link #READONLY_GLOBAL}(4)：只读全局（不加行级限制，写操作由 {@code DataScopeReadOnlyGuardInterceptor} 拒绝）；
 *   <li>{@link #FAMILY}(5)：<b>关系级</b> —— 仅「经 {@code elder_family_bind} 生效绑定可达的老人」及其评估/报告/照护数据
 *       （家属账号；老人本人档案由此可达，替代原先"家属恒查不到数据"的失败即拒）。
 * </ul>
 */
public enum DataScope {
    SELF(1),
    ORG(2),
    ALL(3),
    READONLY_GLOBAL(4),
    FAMILY(5);

    private final int code;

    DataScope(int code) {
        this.code = code;
    }

    public int code() {
        return code;
    }

    public static DataScope of(int code) {
        for (DataScope scope : values()) {
            if (scope.code == code) {
                return scope;
            }
        }
        throw new IllegalArgumentException("未知数据域: " + code);
    }
}
