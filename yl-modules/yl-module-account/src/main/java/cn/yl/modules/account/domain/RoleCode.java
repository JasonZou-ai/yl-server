package cn.yl.modules.account.domain;

/**
 * 五角色枚举（PRD §2.1）。角色码与 data_scope 强制与 sys_role 种子一致，禁止增删或简并。
 *
 * <p>data_scope 取值域：1-本人 / 2-本机构 / 3-全量 / 4-只读全局。本期五角色不占用 3-全量。
 */
public enum RoleCode {
    ELDER("ELDER", 1),
    ASSESSOR("ASSESSOR", 2),
    FAMILY("FAMILY", 1),
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
