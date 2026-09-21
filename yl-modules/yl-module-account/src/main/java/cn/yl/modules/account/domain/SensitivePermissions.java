package cn.yl.modules.account.domain;

import java.util.Set;

/**
 * 敏感操作权限码登记（B2 / ri91pT）。
 *
 * <p>对应 {@code sys_permission.need_second_verify=1} 的 3 个权限点，与 {@code
 * docker/mysql/init/03_seed_rbac.sql} 逐格对齐：导出/批量（{@code data:export}）、作废评估单（{@code
 * evaluation:order:void}）、解绑亲情（{@code account:family:unbind}）。敏感权限点须经第四闸二次验证方可执行。
 */
public final class SensitivePermissions {

    private SensitivePermissions() {}

    private static final Set<String> CODES =
            Set.of(
                    PermissionCode.DATA_EXPORT,
                    PermissionCode.EVAL_ORDER_VOID,
                    PermissionCode.ACCOUNT_FAMILY_UNBIND);

    /** 该权限码是否属于需二次验证的敏感操作。 */
    public static boolean isSensitive(String permCode) {
        return CODES.contains(permCode);
    }

    /** 敏感权限码集合（只读）。 */
    public static Set<String> codes() {
        return CODES;
    }
}
