package cn.yl.modules.account.security;

import cn.yl.api.security.LoginUser;
import cn.yl.modules.account.domain.DataScope;
import cn.yl.modules.account.domain.RoleCode;
import com.baomidou.mybatisplus.extension.plugins.handler.MultiDataPermissionHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SubSelect;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 数据域权限处理器（四道闸·第三闸：数据域）。
 *
 * <p>按 {@link LoginUser#dataScope()} 对 SQL 追加行级过滤条件：1-本人 → {@code 归属列 = 我}；2-本机构 → {@code org_id IN
 * (我的机构)}；3-全量 / 4-只读全局 → 不加限（4 的写拒绝由 {@code DataScopeReadOnlyGuardInterceptor} + 权限矩阵保证）；
 * <b>5-关系级</b> → {@code 锚列 IN (我有效绑定的老人)}（见 {@link #relationSegment}）。
 *
 * <p><b>★ 判定一律按枚举逐值分支，禁止数值比较（D-1 裁决 2）</b>：加入 5-关系级后数值序不再等于宽严序（5 比 2 更窄）， 原写法 {@code if (scope >=
 * DataScope.ALL.code()) return null;} 会把 5 判成"不加限" —— 家属瞬间获得全库可见性， 属跨机构泄露。修复后 3/4
 * 显式枚举，未知值一律落到最严格的"本人"分支而非放行。
 *
 * <p><b>映射策略（保守）</b>：仅对本类显式登记、且列名已在 {@code 02_schema.sql} 核对过的表追加条件；未登记的表一律 不加限，避免误改 SQL
 * 破坏其它模块（字典类表如 {@code eval_item}/{@code gb_rule} 必须对所有账号可读，故不能整体改为失败即拒）。
 * 映射随各模块实现逐步补全，权限矩阵（第二闸）与红线断言仍是第一道防线。
 *
 * <p><b>CR-M2-002 修正三项</b>：
 *
 * <ol>
 *   <li><b>移除错误映射 {@code care_plan → org_id}</b>：{@code care_plan} 表**没有 {@code org_id} 列**（只有
 *       elder_id / suggest_by / confirmed_by）。原映射在 dataScope=2 生效后会拼出 {@code care_plan.org_id IN
 *       (...)} → 未知列 → SQL 报错， 照护方案生成/确认直接 500。这正是"权限载荷补齐后才会显形"的连带缺陷。
 *   <li><b>补全 scope=1 的本人列映射</b>：评估域/账号域凡存在"归属用户列"的表均显式登记，避免 scope=1 账号（老人/家属）读到他人数据。
 *   <li><b>新增 {@link #SELF_DENY_TABLES}（失败即拒）</b>：属于评估/照护/档案域、但**当前无"本人"列可表达**的表，scope=1
 *       一律追加恒假条件，宁可查不到也不越权读到他人数据。
 * </ol>
 *
 * <p><b>D-1 裁决 2 新增（关系级 scope=5）</b>：家属原为 {@code data_scope=1}，而 {@code elder}/{@code eval_report}
 * 等在 scope=1 下属 {@link #SELF_DENY_TABLES}（恒假）→ <b>家属端接口全部实现后仍查不到任何数据</b>。现改为 scope=5 关系级：
 *
 * <ul>
 *   <li>直锚表（自身带 {@code elder_id}，或 {@code elder} 本表用 {@code id}）：{@code 列 IN (<我的老人>)}；
 *   <li>反查锚表（无 {@code elder_id}/{@code org_id}，如 {@code report_dimension_score}、{@code
 *       eval_answer}）：先经父表 （{@code eval_report}/{@code eval_order}）把 {@code elder_id} 收口，再按自身外键列过滤
 *       —— 这样"家属拼他人 reportId / orderId"在<b>数据域层</b>即被拒，而非仅靠服务层校验；
 *   <li>{@code elder_family_bind} 本表按 {@code family_user_id} 过滤（<b>不可</b>用"老人锚列"：同一位老人若还有其他家属绑定， 用
 *       elder_id 过滤会顺带读到他人绑定行）；
 *   <li>其余情形回落到"本人列 → 失败即拒 → 放行"，与 scope=1 同构。
 * </ul>
 *
 * <p><b>D-2 裁决二·方案 A（scope=1 档案域落点）</b>：{@code elder} 原在 {@link #SELF_DENY_TABLES}（恒假）， 导致「{@code
 * elder:archive:read} 已授予 ELDER、户角色却永远读不到自己档案」的静默失效。现新增锚列 {@code elder.user_id}（本人登录账号，可空）并把 {@code
 * elder} 由失败即拒<b>移入</b> {@link #SELF_COLUMN} （{@code elder → user_id}）：scope=1 下渲染为 {@code
 * (elder.user_id = <我>)}，语义结构上可实现。 其余档案域表（{@code elder_family_bind}/{@code
 * elder_authorization}/{@code consent_record}）无"本人"列， 保持失败即拒（明确的保守落点）。
 *
 * <p><b>D-3 报表与监管域补登记（两处，均属"该登记没登记"的静默放行缺陷）</b>：
 *
 * <ul>
 *   <li>① 机构级（scope=2）新增 {@code export_task → org_id}：{@code export_task} 原先只有 {@code
 *       SELF_COLUMN(operator_id)}、无机构列，导致机构管理员查询导出任务时<b>不带任何机构条件</b>（跨机构可读）。按裁决 1-A 为 {@code
 *       export_task} 补可空锚列 {@code org_id} 并登记，与既有 {@code SELF_COLUMN} 并列共存（同 {@code eval_task}
 *       范式）；NULL 行因 {@code IN} 不匹配自然不可见（"NULL 失败即拒"，<b>不</b>给默认值）。
 *   <li>② 本人/关系级（scope=1/5）把 {@code supervise_report} 补入 {@link #SELF_DENY_TABLES}：该表原先只登记 {@code
 *       ORG_COLUMN}，scope=1/5 下走"未登记 → 放行"的<b>暗区</b>（老人/家属可读监管上报报文）。补入后恒假（纯收紧）。
 * </ul>
 *
 * <p>字典类表 {@code gb_rule_mapping}（国标条款→规则条目映射）与 {@code eval_item} 同口径——<b>无任何归属列，属有意放行的字典表，不得登记</b>
 * 进 {@code ORG_COLUMN}/{@code SELF_DENY_TABLES}（否则 B7 规则映射对所有账号不可读）。
 *
 * <p><b>已知残留（登记于 CR-M2-002 §八）</b>：{@code consent_record} 的锚是 {@code subject_type + subject_id} 而非
 * {@code elder_id}，关系级下仍失败即拒（本期家属端无该读取需求）；机构级（scope=2）下 {@code eval_answer}/{@code
 * report_dimension_score}/{@code eval_review_log} 无机构列，由"经 orderId/reportId 反查 + 第二闸权限码"约束。
 *
 * <p><b>D-4 裁决（家属域两锚，★ 零 DDL）</b>：{@code elder_family_bind} 表<b>无</b> {@code org_id} 列，且 {@code
 * family_user_id} 是<b>家属锚</b>（非老人本人）——故该表既无法被 scope=2 的机构列收口，也无法被 scope=1 的本人列 收口：
 *
 * <ul>
 *   <li><b>★F3（scope=2 跨机构可读）</b>：原未登记 → scope=2 走"未登记 → 放行"的暗区，机构管理员<b>跨机构</b>可读/可审绑定行。 现新增 {@link
 *       #ORG_VIA_PARENT} 反查锚，经父表 {@code elder.org_id} 收口（{@code elder_family_bind.elder_id IN
 *       (SELECT p.id FROM elder p WHERE p.org_id IN (myOrgs))}）。
 *   <li><b>★F1（scope=1 功能死路）</b>：原在 {@link #SELF_DENY_TABLES}（恒假），老人持 {@code
 *       account:family:bind:reject} 也永远命不中"本人老人"的待审绑定行。现新增 {@link #SELF_VIA_PARENT} 反查锚，经父表 {@code
 *       elder.user_id} 收口（{@code elder_family_bind.elder_id IN (SELECT p.id FROM elder p WHERE
 *       p.user_id = me)}）。
 * </ul>
 *
 * <p><b>为何零 DDL（不补 {@code org_id} 列）</b>：机构归属是 {@code elder.org_id} 的<b>函数依赖</b>，非本表真属性；补列会
 * 在老人转机构后产生"两个真源"（绑定行 {@code org_id} 陈旧）。反查锚永远与 {@code elder} 一致，故取反查。 scope=5（家属关系级） 下 {@code
 * elder_family_bind} <b>仍按 {@code family_user_id} 过滤</b>（{@link
 * #relationSegment}，<b>不可</b>改用老人锚列：同一 老人若还被其他家属绑定，用 elder_id 过滤会顺带读到<b>他人</b>的绑定行）。
 */
public class DataScopePermissionHandler implements MultiDataPermissionHandler {

    /** 机构级数据域（scope=2）适用的表 → 机构列（列名均已核对 {@code 02_schema.sql}）。 */
    private static final Map<String, String> ORG_COLUMN =
            Map.ofEntries(
                    Map.entry("org", "id"),
                    Map.entry("org_bed", "org_id"),
                    Map.entry("staff", "org_id"),
                    Map.entry("sys_org_member", "org_id"),
                    Map.entry("elder", "org_id"),
                    Map.entry("eval_task", "org_id"),
                    Map.entry("eval_order", "org_id"),
                    Map.entry("eval_report", "org_id"),
                    Map.entry("care_task", "org_id"),
                    Map.entry("supervise_report", "org_id"),
                    // D-3 裁决 1-A：export_task 补可空锚列 org_id 并登记（原无机构列 → 未登记 → scope=2
                    // 静默放行=机构管理员跨机构可读导出任务）。NULL 行因 IN 不匹配自然不可见（"NULL 失败即拒"）。
                    Map.entry("export_task", "org_id"));

    /** 本人级数据域（scope=1）适用的表 → 归属用户列。 */
    private static final Map<String, String> SELF_COLUMN =
            Map.ofEntries(
                    Map.entry("sys_user", "id"),
                    Map.entry("login_log", "user_id"),
                    Map.entry("audit_log", "operator_id"),
                    Map.entry("idempotent_record", "user_id"),
                    Map.entry("track_event", "user_id"),
                    Map.entry("sys_user_role", "user_id"),
                    Map.entry("sys_user_third_party", "user_id"),
                    Map.entry("export_task", "operator_id"),
                    Map.entry("eval_order", "assessor_id"),
                    Map.entry("eval_task", "assessor_id"),
                    Map.entry("eval_review_log", "operator_id"),
                    // D4-R7（R8）：care_plan 已从本集合迁出 —— suggest_by 是「操作者」语义，老人恒不是建议人，
                    // 留在此处会让老人查到的方案恒空（欠权）。改按归属收口，见 SELF_VIA_PARENT。
                    Map.entry("care_task_log", "operator_id"),
                    // D-2 裁决二·方案 A：elder 新增自见锚列 user_id（老人本人的登录账号）。
                    // scope=1（ELDER）下 elder 渲染为 (elder.user_id = <我>)；未绑定时 user_id 为 NULL，
                    // 该行自然不可见（NULL = <我> 恒不成立），无需额外处理。
                    Map.entry("elder", "user_id"));

    /**
     * 机构级（scope=2）**反查锚表** → 本表锚列 + 父表（父表须在 {@link #ORG_COLUMN}，用以把 {@code org_id} 收口）。
     *
     * <p>D-4：{@code elder_family_bind} 无 {@code org_id} 列（机构归属是 {@code elder.org_id} 的函数依赖），原先未登记 →
     * scope=2 下"未登记 → 放行"= 机构管理员<b>跨机构</b>可读/可审（★F3）。零 DDL，经父表收口。
     */
    private static final Map<String, RelationAnchor> ORG_VIA_PARENT =
            Map.ofEntries(Map.entry("elder_family_bind", new RelationAnchor("elder_id", "elder")));

    /**
     * 本人级（scope=1）**反查锚表** → 本表锚列 + 父表（父表须在 {@link #SELF_COLUMN}，用以把 {@code user_id} 收口）。
     *
     * <p>D-4：ELDER(scope=1) 的拒绝端点须命中「本人老人」的绑定行，而 {@code elder_family_bind} 无本人列 → 恒假（★F1 功能死路）。零
     * DDL，经 {@code elder.user_id} 收口。
     */
    private static final Map<String, RelationAnchor> SELF_VIA_PARENT =
            Map.ofEntries(
                    Map.entry("elder_family_bind", new RelationAnchor("elder_id", "elder")),
                    // D4-R7（R7 本体）：老人读自己的评估报告。父表 elder 已在 SELF_COLUMN（user_id），零 DDL。
                    // 维度得分仍由 report_dimension_score 维持判死、经报告聚合接口（dimScores）暴露（D1-c）。
                    Map.entry("eval_report", new RelationAnchor("elder_id", "elder")),
                    // D4-R7（R8 读路径）：照护方案按老人归属收口（自 SELF_COLUMN 的 suggest_by 迁来）。
                    Map.entry("care_plan", new RelationAnchor("elder_id", "elder")));

    /**
     * 老人（scope=1）**专属**归属反查锚 —— D4-R7 F2 方案 A「入口角色分叉」。
     *
     * <p>为何单独一张表而不直接并入 {@link #SELF_VIA_PARENT}：本集合<b>只对 ELDER 生效</b>，员工即便日后落入 scope=1 （例如 {@code
     * dataScope} 聚合由 max 改为 min，或引入单角色员工态）也仍走 {@link #SELF_COLUMN} 的操作者语义 （{@code
     * assessor_id}），不会被收口成"我作为老人"而静默恒空。这是 A 相对 B（直接并入）多出来的那部分保险。
     *
     * <p><b>判定顺序</b>：本集合在 {@link #getSqlSegment} 中<b>先于</b> {@code SELF_COLUMN} 命中 —— {@code
     * eval_order} 同时存在于两处（此处按归属、彼处按操作者），若不前置，老人会先撞上 {@code assessor_id} 而恒空。
     *
     * <p>不变式前提（由 {@code AccountAggregateServiceTest} 锁定）：scope=1 只可能是纯 ELDER 账号。
     */
    private static final Map<String, RelationAnchor> ELDER_SELF_ANCHOR =
            Map.ofEntries(
                    Map.entry("eval_order", new RelationAnchor("elder_id", "elder")),
                    Map.entry("eval_task", new RelationAnchor("elder_id", "elder")));

    /**
     * 需行级过滤但**无"本人"列可表达**的表（scope=1 → 恒假，失败即拒）。
     *
     * <p>为什么不能像未登记表那样放行：这些表属评估/照护/档案域的业务数据，scope=1 的账号（老人）若不加限即可读到**他人**的
     * 评估单、报告维度得分、作答明细与档案。两害相权取失败即拒：宁可查不到，不可越权读到。
     *
     * <p><b>注意</b>：{@code elder} 原属本集合，D-2 裁决二·方案 A 为其新增自见锚列 {@code user_id} 后已迁往 {@link
     * #SELF_COLUMN}（不再失败即拒）。{@code elder_family_bind} 原亦属本集合，D-4 为其新增 {@link #SELF_VIA_PARENT}
     * 反查锚（经 {@code elder.user_id}）后<b>迁出</b>本集合（不再失败即拒）。
     */
    private static final Set<String> SELF_DENY_TABLES =
            Set.of(
                    "eval_report",
                    "report_dimension_score",
                    "eval_answer",
                    "eval_evidence",
                    "consent_record",
                    "elder_authorization",
                    "org_bed",
                    "care_task",
                    // D-3：supervise_report 是监管域报文，scope=1/5（老人/家属）不得读。原仅登记 ORG_COLUMN
                    // （scope=2/4 可读），scope=1/5 下走"未登记 → 放行"= 暗区，故显式补入（纯收紧、零功能损失）。
                    "supervise_report");

    /**
     * 关系级（scope=5）**直锚表** → 本表「老人锚列」：该列取值须落在「我有效绑定的老人 id 集合」内。
     *
     * <p>{@code elder} 本表用主键 {@code id}；其余表用 {@code elder_id}（列名均核对 {@code 02_schema.sql}）。
     */
    private static final Map<String, String> RELATION_ELDER_COLUMN =
            Map.ofEntries(
                    Map.entry("elder", "id"),
                    Map.entry("elder_authorization", "elder_id"),
                    Map.entry("org_bed", "elder_id"),
                    Map.entry("eval_task", "elder_id"),
                    Map.entry("eval_order", "elder_id"),
                    Map.entry("eval_report", "elder_id"),
                    Map.entry("care_plan", "elder_id"),
                    Map.entry("care_task", "elder_id"));

    /**
     * 关系级（scope=5）**反查锚表** → 本表锚列 + 父表（父表须为直锚表，用以把 {@code elder_id} 收口）。
     *
     * <p>这些表没有 {@code elder_id}/{@code org_id}，只能经父资源反查；把反查下沉到数据域层（而非留给服务层）才可以做到 「家属拼他人
     * reportId/orderId 在数据域即被拒」。
     */
    private static final Map<String, RelationAnchor> RELATION_VIA_PARENT =
            Map.ofEntries(
                    Map.entry(
                            "report_dimension_score",
                            new RelationAnchor("report_id", "eval_report")),
                    Map.entry("eval_answer", new RelationAnchor("order_id", "eval_order")),
                    Map.entry("eval_evidence", new RelationAnchor("order_id", "eval_order")),
                    Map.entry("eval_review_log", new RelationAnchor("order_id", "eval_order")),
                    Map.entry("care_task_log", new RelationAnchor("task_id", "care_task")));

    /**
     * 「我当前生效的绑定老人」子查询 —— 关系级可见性的**唯一真源**。
     *
     * <p>生效口径（{@code elder_family_bind}）：{@code status = 2}（0-待确认 / 1-待机构审核 / 2-生效 / 3-已拒绝 /
     * 4-已解绑或过期）且 {@code deleted = 0} 且未过授权截止（{@code auth_end_at IS NULL OR > NOW()}，PRD P04
     * 到期自动解绑）。
     */
    private static final String MY_ELDER_SUBQUERY =
            "SELECT b.elder_id FROM elder_family_bind b"
                    + " WHERE b.family_user_id = %d"
                    + " AND b.status = 2"
                    + " AND b.deleted = 0"
                    + " AND (b.auth_end_at IS NULL OR b.auth_end_at > NOW())";

    @Override
    public Expression getSqlSegment(Table table, Expression where, String mappedStatementId) {
        LoginUser user = currentUser();
        if (user == null) {
            return null;
        }
        int scope = user.dataScope();
        // ★ 逐枚举判定（不可用 scope >= ALL.code()：5-关系级比 2-本机构更窄，数值序已不代表宽严序）
        if (scope == DataScope.ALL.code() || scope == DataScope.READONLY_GLOBAL.code()) {
            return null;
        }
        String tableName = table.getName() == null ? "" : table.getName().toLowerCase(Locale.ROOT);
        if (scope == DataScope.ORG.code()) {
            String column = ORG_COLUMN.get(tableName);
            if (column != null) {
                return orgCondition(table, column, user.orgIds());
            }
            // D-4 ★F3：无机构列的表经父表反查（elder_family_bind → elder.org_id），零 DDL 收口本机构。
            RelationAnchor viaOrg = ORG_VIA_PARENT.get(tableName);
            return viaOrg == null ? null : orgViaParentCondition(table, viaOrg, user.orgIds());
        }
        if (scope == DataScope.FAMILY.code()) {
            return relationSegment(table, tableName, user.userId());
        }
        // D4-R7（F2 方案 A·入口角色分叉）：老人是唯一的「归属对象型」角色，评估单/任务须按 elder_id 归属收口，
        // 而不是 SELF_COLUMN 里的操作者列 assessor_id（老人恒不是录入人 ⇒ 恒空 = 欠权）。
        // 仅对 ELDER 生效，且必须**先于** SELF_COLUMN 命中（eval_order 两处都有登记）。
        if (user.hasAnyRole(RoleCode.ELDER.code())) {
            RelationAnchor elderAnchor = ELDER_SELF_ANCHOR.get(tableName);
            if (elderAnchor != null) {
                return selfViaParentCondition(table, elderAnchor, user.userId());
            }
        }
        String column = SELF_COLUMN.get(tableName);
        if (column != null) {
            return selfCondition(table, column, user.userId());
        }
        // D-4 ★F1：无本人列的表经父表反查（elder_family_bind → elder.user_id），零 DDL 收口"本人老人"。
        RelationAnchor viaSelf = SELF_VIA_PARENT.get(tableName);
        if (viaSelf != null) {
            return selfViaParentCondition(table, viaSelf, user.userId());
        }
        return SELF_DENY_TABLES.contains(tableName) ? denyAll() : null;
    }

    /**
     * 关系级（scope=5）条件的构造顺序：绑定表自身 → 直锚表 → 反查锚表 → 本人列 → 失败即拒 → 放行。
     *
     * <p>顺序有意如此：{@code eval_order}/{@code care_plan} 等既在本人列映射里（{@code assessor_id}/{@code
     * suggest_by}）、 也在直锚表里，家属须按"关系"而非"我是不是录入人"判定，故直锚优先。
     */
    private Expression relationSegment(Table table, String tableName, long familyUserId) {
        if ("elder_family_bind".equals(tableName)) {
            return selfCondition(table, "family_user_id", familyUserId);
        }
        String elderColumn = RELATION_ELDER_COLUMN.get(tableName);
        if (elderColumn != null) {
            return relationCondition(table, elderColumn, null, familyUserId);
        }
        RelationAnchor via = RELATION_VIA_PARENT.get(tableName);
        if (via != null) {
            return relationCondition(table, via.column(), via.parentTable(), familyUserId);
        }
        String selfColumn = SELF_COLUMN.get(tableName);
        if (selfColumn != null) {
            return selfCondition(table, selfColumn, familyUserId);
        }
        return SELF_DENY_TABLES.contains(tableName) ? denyAll() : null;
    }

    /**
     * 构造 {@code table.col IN (我有效绑定的老人)}；有父表时先经父表把 {@code elder_id} 收口。
     *
     * <p>外层列必须限定本表名，子查询内的 {@code b.elder_id} 属绑定表自身、<b>不得</b>被限定 —— 这也是本方法用 jsqlparser
     * 对象拼装（而非字符串拼接整段 SQL）的原因。
     */
    private Expression relationCondition(
            Table table, String column, String parentTable, long familyUserId) {
        String elders = MY_ELDER_SUBQUERY.formatted(familyUserId);
        String inner =
                parentTable == null
                        ? elders
                        : "SELECT p.id FROM "
                                + parentTable
                                + " p WHERE p.elder_id IN ("
                                + elders
                                + ")";
        InExpression in = new InExpression();
        in.setLeftExpression(new Column(table, column));
        in.setRightExpression(subSelect(inner));
        return new Parenthesis(in);
    }

    /**
     * 构造 {@code table.锚列 IN (SELECT p.id FROM 父表 p WHERE p.org_id IN (orgIds))}（scope=2 反查锚，D-4
     * ★F3）。
     *
     * <p>{@code orgIds} 为空 → 恒假（失败即拒）：机构归属缺失时绝不能退化为不加限而读到跨机构数据。
     */
    private Expression orgViaParentCondition(Table table, RelationAnchor via, List<Long> orgIds) {
        if (orgIds == null || orgIds.isEmpty()) {
            return denyAll();
        }
        String inner =
                "SELECT p.id FROM "
                        + via.parentTable()
                        + " p WHERE p.org_id IN ("
                        + joinLongs(orgIds)
                        + ")";
        InExpression in = new InExpression();
        in.setLeftExpression(new Column(table, via.column()));
        in.setRightExpression(subSelect(inner));
        return new Parenthesis(in);
    }

    /**
     * 构造 {@code table.锚列 IN (SELECT p.id FROM 父表 p WHERE p.user_id = 我)}（scope=1 反查锚，D-4 ★F1）。
     *
     * <p>父表无该本人列时 {@code user_id} 为 NULL，子查询为空 → 行自然不可见（"NULL 失败即拒"，无需额外处理）。
     */
    private Expression selfViaParentCondition(Table table, RelationAnchor via, long userId) {
        String inner = "SELECT p.id FROM " + via.parentTable() + " p WHERE p.user_id = " + userId;
        InExpression in = new InExpression();
        in.setLeftExpression(new Column(table, via.column()));
        in.setRightExpression(subSelect(inner));
        return new Parenthesis(in);
    }

    /** 逗号拼接长整型集合（用于反查锚子查询的 {@code IN (...)} 字面量）。 */
    private String joinLongs(List<Long> values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values.get(i));
        }
        return sb.toString();
    }

    /** 把子查询 SQL 解析为 {@link SubSelect} 表达式（渲染为带括号的子查询）。 */
    private Expression subSelect(String sql) {
        try {
            Statement statement = CCJSqlParserUtil.parse(sql);
            if (!(statement instanceof Select select)) {
                throw new IllegalStateException("数据域子查询必须是 SELECT: " + sql);
            }
            SubSelect sub = new SubSelect();
            sub.setSelectBody(select.getSelectBody());
            return sub;
        } catch (JSQLParserException e) {
            throw new IllegalStateException("数据域子查询解析失败: " + sql, e);
        }
    }

    /** {@code table.col IN (orgIds)}；无机构归属时构造恒假条件，避免越权看到任何机构数据。 */
    private Expression orgCondition(Table table, String column, List<Long> orgIds) {
        if (orgIds == null || orgIds.isEmpty()) {
            return denyAll();
        }
        List<Expression> items = new ArrayList<>(orgIds.size());
        for (Long orgId : orgIds) {
            items.add(new LongValue(orgId));
        }
        return new Parenthesis(
                new InExpression(new Column(table, column), new ExpressionList(items)));
    }

    private Expression selfCondition(Table table, String column, long userId) {
        return new Parenthesis(new EqualsTo(new Column(table, column), new LongValue(userId)));
    }

    /** 恒假条件（{@code 1 = 0}）：使该表在本数据域下不可见任何行。 */
    private Expression denyAll() {
        return new Parenthesis(new EqualsTo(new LongValue(1), new LongValue(0)));
    }

    private LoginUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !(authentication.getPrincipal() instanceof LoginUser loginUser)) {
            return null;
        }
        return loginUser;
    }

    /** 关系级反查锚：本表外键列 + 用以收口 {@code elder_id} 的父表。 */
    private record RelationAnchor(String column, String parentTable) {}
}
