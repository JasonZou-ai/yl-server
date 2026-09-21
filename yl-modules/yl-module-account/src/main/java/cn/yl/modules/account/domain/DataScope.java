package cn.yl.modules.account.domain;

/**
 * 数据域枚举（对应 sys_role.data_scope）。用于四道闸第三闸的数据权限隔离。
 *
 * <p>1-本人 / 2-本机构 / 3-全量 / 4-只读全局。数值越大可见范围越广；越权防护以「最严格口径」为基线。
 */
public enum DataScope {
    SELF(1),
    ORG(2),
    ALL(3),
    READONLY_GLOBAL(4);

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
