package cn.yl.modules.account.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yl.api.security.LoginUser;
import java.util.List;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.schema.Table;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 数据域处理器测试（四道闸·第三闸）——CR-M2-002 修正项回归。
 *
 * <p>固定三类结论：
 *
 * <ol>
 *   <li>scope=2（评估员/机构管理员）对评估域表按 {@code org_id IN (orgIds)} 收口；orgIds 为空则<b>恒假</b>（失败即拒）；
 *   <li>scope=1（老人）对"无本人列可表达"的表<b>恒假</b>（改造前是放行，会读到他人数据）；
 *   <li>{@code care_plan} <b>不得</b>被拼接 {@code org_id} 条件（该表没有此列，拼接会直接 SQL 报错）。
 * </ol>
 *
 * <p><b>D-1 裁决 2 追加第四类</b>：scope=5（家属，关系级）—— 直锚表经「我有效绑定的老人」收口、无锚表经父表反查收口、绑定表自身按 {@code
 * family_user_id} 收口，且 <b>5 绝不可被当作"不加限"</b>（原 {@code scope >= ALL} 的数值比较会把 5 判成不限）。
 */
class DataScopePermissionHandlerTest {

    private final DataScopePermissionHandler handler = new DataScopePermissionHandler();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void orgScopeFiltersEvalOrderByOrgIds() {
        login(1L, List.of(1L), 2);

        Expression expr = handler.getSqlSegment(new Table("eval_order"), null, "ms");

        assertEquals("(eval_order.org_id IN (1))", expr.toString());
    }

    @Test
    void orgScopeWithoutOrgMembershipIsFailClosed() {
        login(1L, List.of(), 2);

        Expression expr = handler.getSqlSegment(new Table("eval_order"), null, "ms");

        assertEquals("(1 = 0)", expr.toString(), "无机构归属必须恒假，绝不能退化为不加限");
    }

    /** 关键回归：{@code care_plan} 无 {@code org_id} 列，拼接会报未知列。 */
    @Test
    void orgScopeDoesNotQualifyCarePlanWhichHasNoOrgColumn() {
        login(1L, List.of(1L), 2);

        assertNull(
                handler.getSqlSegment(new Table("care_plan"), null, "ms"),
                "care_plan 无 org_id 列，必须不加限而非拼接错误列（否则 SQL 报错/500）");
    }

    @Test
    void selfScopeFiltersSysUserById() {
        login(900000000000000001L, List.of(1L), 1);

        Expression expr = handler.getSqlSegment(new Table("sys_user"), null, "ms");

        assertEquals("(sys_user.id = 900000000000000001)", expr.toString());
    }

    /**
     * D4-R7（R7 本体）：评估报告改为按老人归属收口 —— 老人可读<b>自己的</b>报告。
     *
     * <p>本用例此前断言 {@code (1 = 0)}（恒假），那是把"欠权"当成正确行为固化：老人读不到自己的报告，而六项作业全绿不报红。 修复后改为<b>正向可达</b>断言——这是
     * R5「防欠权」的要求：只测越权不够，必须同时证明"该读的读得到"。
     *
     * <p>越权方向由 {@code elder_id IN (我作为老人的 id)} 天然保证：他人的报告行不在集合内 ⇒ 查不到。
     */
    @Test
    void selfScopeReadsOwnReportByElderAnchor() {
        login(900000000000000001L, List.of(1L), 1);

        Expression expr = handler.getSqlSegment(new Table("eval_report"), null, "ms");

        assertEquals(
                "(eval_report.elder_id IN (SELECT p.id FROM elder p WHERE p.user_id ="
                        + " 900000000000000001))",
                expr.toString(),
                "scope=1 须按老人归属收口：自己的报告可读、他人的不可读");
    }

    /** D4-R7（R8 读路径）：照护方案自操作者列 suggest_by 迁出，改为按归属收口（老人不再看到空白方案）。 */
    @Test
    void selfScopeReadsOwnCarePlanByElderAnchor() {
        login(900000000000000001L, List.of(1L), 1);

        Expression expr = handler.getSqlSegment(new Table("care_plan"), null, "ms");

        assertEquals(
                "(care_plan.elder_id IN (SELECT p.id FROM elder p WHERE p.user_id ="
                        + " 900000000000000001))",
                expr.toString(),
                "care_plan 不得再按 suggest_by（操作者）收口 —— 老人恒不是建议人，会恒空");
    }

    /**
     * D4-R7（F2 方案 A·入口角色分叉）：ELDER 角色落 scope=1 时，评估单按<b>归属</b>收口（elder_id）， 而不是操作者列
     * assessor_id（老人恒不是录入人 ⇒ 恒空）。
     */
    @Test
    void elderRoleReadsOwnEvalOrderByElderAnchor() {
        login(900000000000000002L, List.of(), 1, List.of("ELDER"));

        Expression expr = handler.getSqlSegment(new Table("eval_order"), null, "ms");

        assertEquals(
                "(eval_order.elder_id IN (SELECT p.id FROM elder p WHERE p.user_id ="
                        + " 900000000000000002))",
                expr.toString(),
                "ELDER 的评估单须按归属收口（契约 openapi:864 assessmentGet 声明 ELDER: self）");
    }

    /**
     * ★ 方案 A 的另一半：员工（ASSESSOR）即便落 scope=1，<b>仍走操作者列</b> assessor_id，不被归属锚收口。
     *
     * <p>钉死这条，是为了保证"入口角色分叉"真的是分叉——若将来 dataScope 聚合语义变化导致员工掉进 scope=1，
     * 员工看到的是自己录入的单（符合预期），而不是被收口成"我作为老人"而静默恒空。
     */
    @Test
    void assessorWithSelfScopeStillUsesOperatorAnchor() {
        login(900000000000000003L, List.of(), 1, List.of("ASSESSOR"));

        Expression expr = handler.getSqlSegment(new Table("eval_order"), null, "ms");

        assertEquals(
                "(eval_order.assessor_id = 900000000000000003)",
                expr.toString(),
                "员工态不得被归属锚收口（角色分叉的边界）");
    }

    /** D4-R7 边界：作答明细 eval_answer 维持判死，不因同批改锚而放开。 */
    @Test
    void evalAnswerStillDeniedForSelfScope() {
        login(900000000000000001L, List.of(1L), 1);

        Expression expr = handler.getSqlSegment(new Table("eval_answer"), null, "ms");

        assertEquals("(1 = 0)", expr.toString(), "eval_answer 维持 SELF_DENY_TABLES 判死");
    }

    /**
     * D-3（裁决 1/§6.2②）：{@code supervise_report} 只登记了机构列，scope=1/5 下曾走"未登记 → 放行"的<b>暗区</b>—— 老人/家属可
     * 读到监管上报报文。修复后进 {@code SELF_DENY_TABLES}（恒假），本用例钉死该语义。
     */
    @Test
    void selfScopeFailsClosedOnSuperviseReport() {
        login(900000000000000001L, List.of(1L), 1);

        Expression expr = handler.getSqlSegment(new Table("supervise_report"), null, "ms");

        assertEquals("(1 = 0)", expr.toString(), "scope=1 不得读到监管上报报文");
    }

    /**
     * D-3（裁决 1-A/§6.2①）：{@code export_task} 补可空锚列 {@code org_id} 并登记进 {@code
     * ORG_COLUMN}，修「机构管理员跨机构可读导出 任务」。本用例钉死 scope=2 下渲染为 {@code (export_task.org_id IN
     * (orgIds))}；NULL 行因 {@code IN} 不匹配自然不可见。
     */
    @Test
    void orgScopeFiltersExportTaskByOrgId() {
        login(1L, List.of(1L), 2);

        Expression expr = handler.getSqlSegment(new Table("export_task"), null, "ms");

        assertEquals("(export_task.org_id IN (1))", expr.toString());
    }

    @Test
    void selfScopeFiltersEvalOrderByAssessor() {
        login(101L, List.of(1L), 1);

        Expression expr = handler.getSqlSegment(new Table("eval_order"), null, "ms");

        assertEquals("(eval_order.assessor_id = 101)", expr.toString());
    }

    /**
     * D-2 裁决二·方案 A：scope=1（ELDER 本人）下 {@code elder} 必须渲染为 {@code (elder.user_id = <我>)}。
     *
     * <p>改造前 {@code elder} 属 {@code SELF_DENY_TABLES}（恒假）——老人虽持 {@code elder:archive:read}
     * 也永远读不到自己的档案（"权限已授、语义不可达"的静默失效）。本用例钉死新增锚列 {@code user_id} 的 SQL 语义， 作为 {@code
     * D-2R1}（老人↔账号绑定写入流程）落地前的确定性正确性证明。
     */
    @Test
    void selfScopeFiltersElderByUserId() {
        login(900000000000000001L, List.of(1L), 1);

        Expression expr = handler.getSqlSegment(new Table("elder"), null, "ms");

        assertEquals("(elder.user_id = 900000000000000001)", expr.toString());
    }

    @Test
    void globalScopesAreUnfiltered() {
        login(1L, List.of(1L), 3);
        assertNull(handler.getSqlSegment(new Table("eval_order"), null, "ms"), "scope=3 全量不加限");

        login(1L, List.of(1L), 4);
        assertNull(handler.getSqlSegment(new Table("eval_order"), null, "ms"), "scope=4 只读全局不加限");
    }

    @Test
    void anonymousRequestIsUnfiltered() {
        assertNull(handler.getSqlSegment(new Table("eval_order"), null, "ms"));
    }

    // ---------------- D-1 裁决 2：关系级（scope=5，家属）----------------

    /**
     * ★ 数值序陷阱回归：{@code scope=5} <b>绝不能</b>被当作"不加限"。
     *
     * <p>改造前判定为 {@code if (scope >= DataScope.ALL.code()) return null;} —— 关系级 5 ≥ 3 成立，家属会拿到 全库可见性
     * （跨机构泄露）。本用例就是钉死这一点：升级枚举后若有人把 3/4 判定写回数值比较，此处立即变红。
     */
    @Test
    void familyScopeIsNeverTreatedAsUnlimited() {
        login(900000000000000009L, List.of(), 5);

        Expression expr = handler.getSqlSegment(new Table("eval_report"), null, "ms");

        assertNotNull(expr, "scope=5（关系级）必须加行级条件，绝不可退化为不加限");
        assertTrue(
                expr.toString().contains("elder_family_bind"),
                "关系级条件必须经 elder_family_bind 收口，实际：" + expr);
    }

    /** 直锚表：报告按「我绑定的老人」过滤。 */
    @Test
    void familyScopeFiltersReportByBoundElders() {
        login(900000000000000009L, List.of(), 5);

        String sql = handler.getSqlSegment(new Table("eval_report"), null, "ms").toString();

        assertEquals(
                "(eval_report.elder_id IN (SELECT b.elder_id FROM elder_family_bind b"
                        + " WHERE b.family_user_id = 900000000000000009"
                        + " AND b.status = 2 AND b.deleted = 0"
                        + " AND (b.auth_end_at IS NULL OR b.auth_end_at > NOW())))",
                sql);
    }

    /**
     * 绑定表<b>自身</b>必须按 {@code family_user_id} 过滤，而不是按"老人锚列"。
     *
     * <p>反例代价：若用 {@code elder_id IN (我的老人)}，同一位老人若还被其他家属绑定，本家属会顺带读到<b>他人</b>的绑定行 （含其 user_id
     * 与关系），属个人信息泄露。
     */
    @Test
    void familyScopeFiltersBindingTableByFamilyUserItself() {
        login(900000000000000009L, List.of(), 5);

        Expression expr = handler.getSqlSegment(new Table("elder_family_bind"), null, "ms");

        assertEquals("(elder_family_bind.family_user_id = 900000000000000009)", expr.toString());
    }

    /**
     * 反查锚表：{@code report_dimension_score} 无 {@code elder_id}，须先经 {@code eval_report} 收口。
     *
     * <p>这正是"家属拼他人 reportId 必须在数据域层被拒"的落点 —— 他人的 reportId 反查不到行，恒不匹配。
     */
    @Test
    void familyScopeReverseLooksUpParentTable() {
        login(900000000000000009L, List.of(), 5);

        String sql =
                handler.getSqlSegment(new Table("report_dimension_score"), null, "ms").toString();

        assertTrue(
                sql.startsWith(
                        "(report_dimension_score.report_id IN (SELECT p.id FROM eval_report p"),
                sql);
        assertTrue(sql.contains("p.elder_id IN (SELECT b.elder_id FROM elder_family_bind b"), sql);
    }

    /** {@code eval_answer} 经 {@code eval_order} 收口（作答明细的越权面）。 */
    @Test
    void familyScopeReverseLooksUpOrderForAnswers() {
        login(900000000000000009L, List.of(), 5);

        String sql = handler.getSqlSegment(new Table("eval_answer"), null, "ms").toString();

        assertTrue(sql.startsWith("(eval_answer.order_id IN (SELECT p.id FROM eval_order p"), sql);
    }

    /** 关系级下无锚可表达的档案域表仍失败即拒（{@code consent_record} 用 subject_type/subject_id，非 elder_id）。 */
    @Test
    void familyScopeFailsClosedOnAnchorlessArchiveTable() {
        login(900000000000000009L, List.of(), 5);

        assertEquals(
                "(1 = 0)",
                handler.getSqlSegment(new Table("consent_record"), null, "ms").toString(),
                "无锚可表达时必须失败即拒");
    }

    /** 字典类表不参与数据域过滤（{@code eval_item} 未登记 → 放行），家属端取题库不被拦。 */
    @Test
    void familyScopeLeavesDictionaryTablesUnfiltered() {
        login(900000000000000009L, List.of(), 5);

        assertNull(handler.getSqlSegment(new Table("eval_item"), null, "ms"));
    }

    /**
     * 未知/越界 scope 值一律落到最严格的"本人"分支，不得放行。
     *
     * <p>探针改用 {@code eval_answer}：D4-R7 后 {@code eval_report} 在本人分支已<b>可达</b>（按归属收口）， 用它再断言 {@code
     * (1 = 0)} 会误报。判死语义请用仍然 fail-closed 的表来钉。
     */
    @Test
    void unknownScopeFallsBackToStrictestBranch() {
        login(900000000000000009L, List.of(), 99);

        assertEquals(
                "(1 = 0)",
                handler.getSqlSegment(new Table("eval_answer"), null, "ms").toString(),
                "未知 scope 不得退化为不加限");
    }

    // ---------------- D-4 T01：数据域两锚（★F1 / ★F3）----------------

    /**
     * ★F3 修复（D-4 T01）：scope=2（机构管理员）下 {@code elder_family_bind} 无 {@code org_id} 列， 原先未登记 →
     * "未登记即放行" → 机构管理员可跨机构读/审绑定行。现经父表 {@code elder.org_id} 反查收口（零 DDL）。
     */
    @Test
    void orgScopeAnchorsElderFamilyBindViaElderOrgId() {
        login(1L, List.of(1L), 2);

        Expression expr = handler.getSqlSegment(new Table("elder_family_bind"), null, "ms");

        assertEquals(
                "(elder_family_bind.elder_id IN (SELECT p.id FROM elder p WHERE p.org_id IN (1)))",
                expr.toString());
    }

    /** scope=2 且无机构归属：反查锚也必须失败即拒（不得因 orgIds 为空而退化为不加限）。 */
    @Test
    void orgScopeAnchorsElderFamilyBindFailClosedWithoutOrg() {
        login(1L, List.of(), 2);

        Expression expr = handler.getSqlSegment(new Table("elder_family_bind"), null, "ms");

        assertEquals("(1 = 0)", expr.toString());
    }

    /**
     * ★F1 修复（D-4 T01）：scope=1（ELDER 本人）下 {@code elder_family_bind} 无"本人"列， 原先在 {@code
     * SELF_DENY_TABLES}（恒假）→ 老人无法拒绝他人对自己的绑定申请（功能死路）。 现经父表 {@code elder.user_id} 反查收口（零 DDL）。
     */
    @Test
    void selfScopeAnchorsElderFamilyBindViaElderUserId() {
        login(900000000000000003L, List.of(1L), 1);

        Expression expr = handler.getSqlSegment(new Table("elder_family_bind"), null, "ms");

        assertEquals(
                "(elder_family_bind.elder_id IN (SELECT p.id FROM elder p WHERE p.user_id = 900000000000000003))",
                expr.toString());
    }

    private void login(long userId, List<Long> orgIds, int dataScope) {
        login(userId, orgIds, dataScope, List.of("ASSESSOR"));
    }

    /**
     * 带角色码的登录（D4-R7 F2 方案 A「入口角色分叉」所需）。
     *
     * <p>默认重载固定为 ASSESSOR —— 这正好用来钉死另一半语义：<b>员工即便落 scope=1 也仍走操作者列</b>，不被归属锚收口。
     */
    private void login(long userId, List<Long> orgIds, int dataScope, List<String> roles) {
        LoginUser user = new LoginUser(userId, 1L, roles, List.of(), orgIds, dataScope);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
