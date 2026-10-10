package cn.yl.modules.account.domain;

/**
 * 五角色枚举（PRD §2.1）。角色码与 data_scope 强制与 sys_role 种子一致，禁止增删或简并。
 *
 * <p>data_scope 取值域：1-本人 / 2-本机构 / 3-全量 / 4-只读全局 / 5-关系级（家属）。本期五角色不占用 3-全量。
 *
 * <p><b>FAMILY = 5（关系级）</b>：与 {@code 03_seed_rbac.sql} 的 {@code sys_role.data_scope='FAMILY'→5}
 * （D-1 裁决 2）逐格一致。关系级使第三闸对 {@code elder_family_bind} 走 {@code family_user_id = me} 收口、对 {@code
 * eval_report}/{@code care_plan} 走 {@code elder_id IN (我绑定的老人)} 收口（D-4 家属读链路的唯一依据）。
 */
public enum RoleCode {
    ELDER("ELDER", 1),
    ASSESSOR("ASSESSOR", 2),
    FAMILY("FAMILY", 5),
    ORG_ADMIN("ORG_ADMIN", 2),
    SUPERVISOR("SUPERVISOR", 4);

    private final String code;
    private final int dataScope;

    RoleCode(String code, int dataScope) {
        this.code = code;
        this.dataScope = dataScope;
    }

    public String code() {
        return code;
    }

    public int dataScope() {
        return dataScope;
    }

    public static RoleCode of(String code) {
        for (RoleCode role : values()) {
            if (role.code.equals(code)) {
                return role;
            }
        }
        throw new IllegalArgumentException("未知角色码: " + code);
    }
}
