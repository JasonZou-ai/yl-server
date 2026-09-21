package cn.yl.modules.account.domain;

/**
 * 权限码常量（PRD §2.2 权限矩阵）。与 sys_permission 种子 16 行逐格对齐，禁止私自增删。
 *
 * <p>命名：{@code <module>:<resource>:<action>[:受限后缀]}；受限/需审批 → {@code :apply}/{@code :suggest}； 只读 →
 * {@code :read}。{@code data:export}/{@code evaluation:order:void}/{@code account:family:unbind}
 * 为敏感操作权限点（need_second_verify=1）。
 */
public final class PermissionCode {

    private PermissionCode() {}

    public static final String REPORT_VIEW = "report:view";
    public static final String EVAL_ORDER_CREATE = "evaluation:order:create";
    public static final String EVAL_ORDER_CREATE_APPLY = "evaluation:order:create:apply";
    public static final String EVAL_ITEM_INPUT = "evaluation:item:input";
    public static final String EVAL_ORDER_REVIEW = "evaluation:order:review";
    public static final String EVAL_ORDER_REVIEW_READ = "evaluation:order:review:read";
    public static final String CARE_PLAN_EDIT = "care:plan:edit";
    public static final String CARE_PLAN_EDIT_SUGGEST = "care:plan:edit:suggest";
    public static final String ACCOUNT_ORG_MANAGE = "account:org:manage";
    public static final String ACCOUNT_ORG_MANAGE_READ = "account:org:manage:read";
    public static final String SUPERVISE_REPORT_SUBMIT = "supervise:report:submit";
    public static final String DATA_EXPORT = "data:export";
    public static final String ACCOUNT_FAMILY_BIND = "account:family:bind";
    public static final String ACCOUNT_FAMILY_BIND_REJECT = "account:family:bind:reject";
    public static final String EVAL_ORDER_VOID = "evaluation:order:void";
    public static final String ACCOUNT_FAMILY_UNBIND = "account:family:unbind";
}
