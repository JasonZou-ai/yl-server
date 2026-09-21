package cn.yl.modules.account.domain;

/**
 * 权限码常量（PRD v1.1 §2.2 权限矩阵）。与 {@code sys_permission} 种子 22 行逐格对齐，禁止私自增删。
 *
 * <p>共 22 个权限点：前 16 个为 PRD v1.1 原矩阵派生（id 2001–2016）；后 6 个由 CR-M2-001 补齐（id 2017–2022，2026-09-21 CCB
 * 会签 + PM 签发通过）。
 *
 * <p>命名：{@code <module>:<resource>:<action>[:受限后缀]}；受限/需审批 → {@code :apply}/{@code :suggest}； 只读 →
 * {@code :read}。敏感操作权限点（{@code need_second_verify=1}，共 4 个）见 {@link SensitivePermissions}。
 */
public final class PermissionCode {

    private PermissionCode() {}

    // ---------- PRD v1.1 §2.2 原矩阵派生（id 2001–2016，16 个） ----------

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

    // ---------- CR-M2-001 补齐（id 2017–2022，6 个） ----------

    /** 老人建档（矩阵行 10）。 */
    public static final String ELDER_ARCHIVE_CREATE = "elder:archive:create";

    /** 发起代办建档申请（矩阵行 10，家属受限态）。 */
    public static final String ELDER_ARCHIVE_CREATE_APPLY = "elder:archive:create:apply";

    /**
     * 查看老人档案（矩阵行 11，五角色全授予）。
     *
     * <p><b>可见范围不靠权限点区分</b>，由第三闸 {@code data_scope} 控制（老人/家属=本人，评估员/机构管理员=本机构，监管=只读全局）； 批量拉档风险由
     * {@code ArchiveAccessRateGuard} 行为异常检测兜底（CR-M2-001 §2.5）。
     */
    public static final String ELDER_ARCHIVE_READ = "elder:archive:read";

    /** 评估任务列表查看（矩阵行 12）。 */
    public static final String EVAL_TASK_READ = "evaluation:task:read";

    /** 查看敏感字段明文（矩阵行 13，敏感点：须二次验证 + 留痕）。 */
    public static final String DATA_REVEAL = "data:reveal";

    /** 国标规则只读 / 条款回溯（矩阵行 14）。 */
    public static final String RULE_VIEW = "rule:view";
}
