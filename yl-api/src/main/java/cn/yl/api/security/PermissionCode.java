package cn.yl.api.security;

/**
 * 权限码常量（PRD v1.1 §2.2 权限矩阵）。与 {@code sys_permission} 种子 22 行逐格对齐，禁止私自增删。
 *
 * <p>共 22 个权限点：前 16 个为 PRD v1.1 原矩阵派生（id 2001–2016）；后 6 个由 CR-M2-001 补齐（id 2017–2022，2026-09-21 CCB
 * 会签 + PM 签发通过）。
 *
 * <p>命名：{@code <module>:<resource>:<action>[:受限后缀]}；受限/需审批 → {@code :apply}/{@code :suggest}； 只读 →
 * {@code :read}。敏感操作权限点（{@code need_second_verify=1}，共 4 个）见 {@code
 * cn.yl.modules.account.domain.SensitivePermissions}。
 *
 * <p><b>位置说明（CR-M2-002）</b>：本常量类原在 {@code cn.yl.modules.account.domain}，因权限码现由多个业务模块（evaluation
 * 及其后的 care/report/archive）在 {@link RequiresPermission} 上直接引用，而各业务模块只依赖共享层 {@code yl-api}（不互相依赖），
 * 故上移至 {@code cn.yl.api.security}。数值与 {@code 03_seed_rbac.sql} 的对应关系不变。
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

    // ---------- CR-M2-009 B-1-a 补齐（id 2023，1 个） ----------

    /**
     * 查看<b>所属机构公开联系方式</b>（{@code rqZ0a3}-B5 / {@code rR1a6L} 残留⑥，五角色全授予）。
     *
     * <p><b>为什么不复用 {@link #ACCOUNT_ORG_MANAGE_READ}</b>：该码语义是「机构管理」，授予 ELDER/FAMILY 会把管理语义外泄 ——
     * 本项目既有教训：<b>权限码被多角色共享而语义只属其一 ⇒ 应改权限码，而不是改授权</b>。故新增本窄语义只读码。
     *
     * <p><b>可见范围不靠权限点区分</b>：端点<b>不接受 {@code orgId}</b>（{@code org} 表只登记 {@code
     * ORG_COLUMN}，scope=1/5 下第三闸走「未登记 → 放行」暗区，一旦接受参数即成跨机构读），归属由登录态自证，见 {@code OrgContactService}。
     *
     * <p><b>最小字段集</b>：只返回机构名 / 联系人 / 地址；<b>不含</b> {@code contact_phone_enc}（密文列，{@code OrgView}
     * 亦刻意不返回，避免泄露可解密线索）。非敏感点 ⇒ 不要求二次验证。
     */
    public static final String ORG_CONTACT_READ = "org:contact:read";
}
