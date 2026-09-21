package cn.yl.api.security;

/** 多权限码之间的判定逻辑（@RequiresPermission 使用）。 */
public enum Logical {

    /** 全部满足。 */
    ALL,

    /** 任一满足。 */
    OR
}
