package cn.yl.bootstrap.retention;

import static org.assertj.core.api.Assertions.assertThat;

import cn.yl.modules.account.mapper.SysUserThirdPartyMapper;
import cn.yl.modules.account.service.retention.ThirdPartyRetentionCleanupTask;
import cn.yl.modules.archive.mapper.ElderFamilyBindMapper;
import cn.yl.modules.archive.service.retention.FamilyBindingExpireTask;
import cn.yl.modules.evaluation.mapper.EvalOrderMapper;
import cn.yl.modules.evaluation.service.retention.EvaluationArchiveTask;
import cn.yl.modules.report.domain.retention.ExpiredExportTaskReaper;
import cn.yl.modules.report.domain.retention.RetentionReferencePort;
import cn.yl.modules.report.mapper.RetentionReferenceMapper;
import cn.yl.modules.supervise.mapper.SuperviseReportMapper;
import cn.yl.modules.supervise.service.retention.SuperviseReportArchiveTask;
import java.lang.reflect.Method;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Set;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 保留期链路 · 手写 SQL 条件的 <b>DB 级可重复回归门禁</b>（闭环 D5-R1）。
 *
 * <h3>为什么需要它</h3>
 *
 * <p>保留期链路的 4 个动作各靠一张表的手写 SQL 落地，其幂等 / 守卫条件此前<b>无任何自动化覆盖</b>： 既有单测（{@code *TaskTest}）一律注入 Fake /
 * mock 端口，<b>永不触达真实 SQL</b>。QA 突变实证——摘掉 ④ 的 {@code deleted = 0}、③ 的 {@code archived_at IS NULL}、或把
 * {@code SET status = 4} 改成 {@code SET status = 4, deleted = 1}，{@code mvn -o verify} <b>仍 270/0
 * SUCCESS</b>。本类补上这块盲区：<b>种真实数据 → 调真任务 → 读真实 DB 断言</b>。
 *
 * <h3>覆盖的 4 条手写 SQL 约束</h3>
 *
 * <ol>
 *   <li><b>ER-14</b> {@code SysUserThirdPartyMapper#purgeExpired}：{@code retain_until IS NOT NULL
 *       AND retain_until < cutoff} —— <b>物理 DELETE</b>；
 *   <li><b>ER-03</b> {@code EvalOrderMapper#archiveExpired}：{@code ... AND status = 'PUBLISHED'} →
 *       {@code status='ARCHIVED'}；
 *   <li><b>D-5③</b> {@code SuperviseReportMapper#archiveExpired}：{@code archived_at IS NULL AND
 *       retain_until IS NOT NULL AND retain_until < cutoff} → 写 {@code archived_at}（<b>不改
 *       status</b>）；
 *   <li><b>D-5④</b> {@code ElderFamilyBindMapper#expireExpired}：{@code status = 2 AND deleted = 0
 *       AND auth_end_at IS NOT NULL AND auth_end_at < cutoff} → {@code status = 4}（<b>绝不改 deleted /
 *       active_uk</b>）。
 * </ol>
 *
 * <h3>用例</h3>
 *
 * <p>T1–T4 逐动作验「命中 + 反例 + 幂等」；<b>T5</b> 是<b>静态</b>守卫（反射读 SQL 注解文本，防关键 WHERE 条件被 静默摘除——其中 ① 的 {@code
 * retain_until IS NOT NULL} 在行为层不可区分，只能这样守）；<b>T6</b> 守住 「到期时间为 NULL ⇒ 永不被处置」的<b>语义</b>（三张表各一行）。
 *
 * <p><b>T7–T9（P2-7 保留期链路第 5 个动作 · 孤儿文件回收）</b>：<b>T7</b> 验 {@code export_task} 到期标记（{@code expire_at
 * < cutoff AND status ≠ 4} ⇒ {@code status = 4}）的命中 / 反例 / 幂等；<b>T8</b> 验报告引用投影<b>排除 {@code deleted
 * = 1}</b> ——这正是「手写 {@code @Select} 不走全局逻辑删除、必须显式写 {@code deleted = 0}」的守卫；<b>T9</b> 验导出引用投影<b>不含
 * {@code status = 4}</b> 行。
 *
 * <h3>运行方式（不进默认 surefire）</h3>
 *
 * <p>类名以 {@code IT} 结尾 ⇒ 不被 surefire 默认 includes（{@code **}{@code /*Test.java}）命中，故 {@code mvn -o
 * verify} 的既有基线<b>不受影响</b>；仅当显式启用 {@code db-it} profile（{@code includes=**}{@code /*IT.java}）时执行。
 * 便捷入口：{@code bash scripts/verify-retention-chain.sh}（需 MySQL / Redis 在跑，连接参数由凭据文件经环境变量注入）。
 *
 * <h3>事务 / 数据卫生</h3>
 *
 * <p>{@code @Transactional(propagation = NOT_SUPPORTED)}：<b>不</b>让测试包在回滚事务里——否则任务写的数据读不到、
 * 断言看不到「已提交」状态。所有种数据用专用 id 前缀（{@code 9980000009xxx}，断言只认这些行、不依赖库中既有业务数据）， {@code @AfterEach}
 * 按前缀清理表数据与本次新增的审计行，保证<b>幂等、可重复、自清理</b>。
 */
@SpringBootTest(
        properties = {
            // 关掉链路壳的定时触发（避免测试期真的被 cron 打到）；4 个任务 bean 各自 matchIfMissing=true，仍保留。
            "yl.retention.chain.enabled=false",
            // 测试期静音 SQL 日志，减少噪声。
            "mybatis-plus.configuration.log-impl=org.apache.ibatis.logging.nologging.NoLoggingImpl"
        })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class RetentionChainDbIT {

    /** T3 已归档行预设的 archived_at（用于断言未被重置为 now）。 */
    private static final LocalDateTime FIXED_ARCHIVED_AT = LocalDateTime.of(2020, 6, 1, 0, 0, 0);

    /** T1 ER-14：三方绑定。 */
    private static final long T1_LO = 9980000009000L;

    private static final long T1_HI = 9980000009099L;

    private static final long T1_HIT_A = 9980000009001L;

    private static final long T1_HIT_B = 9980000009002L;

    private static final long T1_RETAIN_NULL = 9980000009003L;

    private static final long T1_FUTURE = 9980000009004L;

    /** T2 ER-03：评估单。 */
    private static final long T2_LO = 9980000009100L;

    private static final long T2_HI = 9980000009199L;

    private static final long T2_HIT = 9980000009101L;

    private static final long T2_FUTURE_PUBLISHED = 9980000009102L;

    private static final long T2_EXPIRED_VOID = 9980000009103L;

    private static final long T2_EXPIRED_SUBMITTED = 9980000009104L;

    /** T3 D-5③：监管上报。 */
    private static final long T3_LO = 9980000009200L;

    private static final long T3_HI = 9980000009299L;

    private static final long T3_HIT_A = 9980000009201L;

    private static final long T3_HIT_B = 9980000009202L;

    private static final long T3_FUTURE = 9980000009203L;

    private static final long T3_RETAIN_NULL = 9980000009204L;

    private static final long T3_ALREADY_ARCHIVED = 9980000009205L;

    /** T4 D-5④：亲情绑定。 */
    private static final long T4_LO = 9980000009300L;

    private static final long T4_HI = 9980000009399L;

    private static final long[] T4_HITS = {9980000009301L, 9980000009302L, 9980000009303L};

    private static final long T4_AUTH_NULL = 9980000009304L;

    private static final long T4_FUTURE = 9980000009305L;

    private static final long T4_STATUS4 = 9980000009306L;

    private static final long T4_STATUS0 = 9980000009307L;

    private static final long T4_STATUS3 = 9980000009308L;

    private static final long T4_STATUS1 = 9980000009309L;

    private static final long T4_DELETED1 = 9980000009310L;

    /** T6 NULL 到期语义（复用 T1/T3/T4 的清理区间，故只需 id 不冲突）。 */
    private static final long T6_TP_RETAIN_NULL = 9980000009005L;

    private static final long T6_SR_RETAIN_NULL = 9980000009206L;

    private static final long T6_FB_AUTH_NULL = 9980000009311L;

    /** T7 P2-7：导出任务到期标记（expire_at < now 且 status ≠ 4 ⇒ status = 4）。 */
    private static final long T7_LO = 9980000009400L;

    private static final long T7_HI = 9980000009499L;

    private static final long T7_HIT_A = 9980000009401L;

    private static final long T7_HIT_B = 9980000009402L;

    private static final long T7_FUTURE = 9980000009403L;

    private static final long T7_EXPIRE_NULL = 9980000009404L;

    private static final long T7_ALREADY_4 = 9980000009405L;

    /** T9 P2-7：导出引用投影（status ≠ 4 ⇒ 在用）。 */
    private static final long T9_LO = 9980000009500L;

    private static final long T9_HI = 9980000009599L;

    private static final long T9_ACTIVE = 9980000009501L;

    private static final long T9_EXPIRED = 9980000009502L;

    /** T8 P2-7：报告引用投影（deleted = 0 ⇒ 在用；手写 SQL 必须显式写 deleted = 0）。 */
    private static final long T8_LO = 9980000009600L;

    private static final long T8_HI = 9980000009699L;

    private static final long T8_ACTIVE_NULL_PDF = 9980000009601L;

    private static final long T8_ACTIVE_WITH_PDF = 9980000009602L;

    private static final long T8_DELETED = 9980000009603L;

    private final JdbcTemplate jdbc;

    private final ThirdPartyRetentionCleanupTask thirdPartyTask;

    private final EvaluationArchiveTask evaluationArchiveTask;

    private final SuperviseReportArchiveTask superviseReportArchiveTask;

    private final FamilyBindingExpireTask familyBindingExpireTask;

    private final RetentionReferencePort retentionReferencePort;

    private final ExpiredExportTaskReaper expiredExportTaskReaper;

    private long auditMaxIdAtStart;

    @Autowired
    RetentionChainDbIT(
            JdbcTemplate jdbc,
            ThirdPartyRetentionCleanupTask thirdPartyTask,
            EvaluationArchiveTask evaluationArchiveTask,
            SuperviseReportArchiveTask superviseReportArchiveTask,
            FamilyBindingExpireTask familyBindingExpireTask,
            RetentionReferencePort retentionReferencePort,
            ExpiredExportTaskReaper expiredExportTaskReaper) {
        this.jdbc = jdbc;
        this.thirdPartyTask = thirdPartyTask;
        this.evaluationArchiveTask = evaluationArchiveTask;
        this.superviseReportArchiveTask = superviseReportArchiveTask;
        this.familyBindingExpireTask = familyBindingExpireTask;
        this.retentionReferencePort = retentionReferencePort;
        this.expiredExportTaskReaper = expiredExportTaskReaper;
    }

    @BeforeEach
    void captureAuditHighWaterMark() {
        Long max = jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM audit_log", Long.class);
        auditMaxIdAtStart = max == null ? 0L : max;
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM sys_user_third_party WHERE id BETWEEN ? AND ?", T1_LO, T1_HI);
        jdbc.update("DELETE FROM eval_order WHERE id BETWEEN ? AND ?", T2_LO, T2_HI);
        jdbc.update("DELETE FROM supervise_report WHERE id BETWEEN ? AND ?", T3_LO, T3_HI);
        jdbc.update("DELETE FROM elder_family_bind WHERE id BETWEEN ? AND ?", T4_LO, T4_HI);
        // P2-7（T7/T9）：导出任务；T8：评估报告。
        jdbc.update("DELETE FROM export_task WHERE id BETWEEN ? AND ?", T7_LO, T9_HI);
        jdbc.update("DELETE FROM eval_report WHERE id BETWEEN ? AND ?", T8_LO, T8_HI);
        // 仅清理本次运行新增的三方清理审计行（ER-14 任务命中 > 0 时会写审计），不动历史数据。
        jdbc.update(
                "DELETE FROM audit_log WHERE action = 'THIRD_PARTY_PURGE' AND id > ?",
                auditMaxIdAtStart);
    }

    // ------------------------------------------------------------------ T1 · ER-14

    @Test
    @DisplayName("T1 / ER-14：仅物理删除 retain_until 非空且已过期的三方绑定；幂等")
    void t1Er14PurgesOnlyExpiredNonNullRowsPhysically() {
        LocalDateTime now = LocalDateTime.now();
        insertThirdParty(T1_HIT_A, "WECHAT", 90011L, now.minusDays(1));
        insertThirdParty(T1_HIT_B, "DOUYIN", 90012L, now.minusHours(2));
        insertThirdPartyWithNullRetain(T1_RETAIN_NULL, "APPLE", 90013L);
        insertThirdParty(T1_FUTURE, "WECHAT", 90014L, now.plusDays(30));
        assertThat(countIn("sys_user_third_party", T1_LO, T1_HI)).isEqualTo(4);

        int purged = thirdPartyTask.purgeExpiredBindings();

        assertThat(purged).as("仅 2 条过期行命中").isEqualTo(2);
        assertThat(rowExists("sys_user_third_party", T1_HIT_A)).as("过期行 A 被物理删除").isFalse();
        assertThat(rowExists("sys_user_third_party", T1_HIT_B)).as("过期行 B 被物理删除").isFalse();
        assertThat(rowExists("sys_user_third_party", T1_RETAIN_NULL))
                .as("retain_until IS NULL 的行永不清理")
                .isTrue();
        assertThat(rowExists("sys_user_third_party", T1_FUTURE)).as("未到期行保留").isTrue();

        int again = thirdPartyTask.purgeExpiredBindings();
        assertThat(again).as("幂等：第二次无到期数据").isZero();
        assertThat(countIn("sys_user_third_party", T1_LO, T1_HI)).isEqualTo(2);
    }

    // ------------------------------------------------------------------ T2 · ER-03 评估单

    @Test
    @DisplayName("T2 / ER-03：仅将已过期的 PUBLISHED 评估单转 ARCHIVED；反例不变；幂等")
    void t2Er03ArchivesOnlyExpiredPublishedOrders() {
        LocalDateTime now = LocalDateTime.now();
        insertEvalOrder(T2_HIT, "PUBLISHED", now.minusDays(1));
        insertEvalOrder(T2_FUTURE_PUBLISHED, "PUBLISHED", now.plusDays(30));
        insertEvalOrder(T2_EXPIRED_VOID, "VOID", now.minusDays(1));
        insertEvalOrder(T2_EXPIRED_SUBMITTED, "SUBMITTED", now.minusDays(1));
        assertThat(countIn("eval_order", T2_LO, T2_HI)).isEqualTo(4);

        int archived = evaluationArchiveTask.archiveExpiredOrders();

        assertThat(archived).as("仅 1 条命中").isEqualTo(1);
        assertThat(evalStatusOf(T2_HIT)).as("命中行转 ARCHIVED").isEqualTo("ARCHIVED");
        assertThat(evalStatusOf(T2_FUTURE_PUBLISHED))
                .as("未到期的 PUBLISHED 不归档")
                .isEqualTo("PUBLISHED");
        assertThat(evalStatusOf(T2_EXPIRED_VOID)).as("到期的 VOID 不归档").isEqualTo("VOID");
        assertThat(evalStatusOf(T2_EXPIRED_SUBMITTED))
                .as("到期的 SUBMITTED 不归档")
                .isEqualTo("SUBMITTED");

        int again = evaluationArchiveTask.archiveExpiredOrders();
        assertThat(again).as("幂等：第二次无到期评估单").isZero();
    }

    // ------------------------------------------------------------------ T3 · D-5③ 监管上报

    @Test
    @DisplayName("T3 / D-5③：仅对未归档且已过期的监管上报写 archived_at（行保留、status 不变）；幂等")
    void t3D5SuperviseArchiveMarksOnlyUnarchivedExpiredRows() {
        LocalDateTime now = LocalDateTime.now();
        insertSuperviseReport(T3_HIT_A, "2020-01", 92001L, 1, now.minusDays(1), null);
        insertSuperviseReport(T3_HIT_B, "2020-02", 92002L, 1, now.minusHours(3), null);
        insertSuperviseReport(T3_FUTURE, "2020-03", 92003L, 1, now.plusDays(30), null);
        insertSuperviseReport(T3_RETAIN_NULL, "2020-04", 92004L, 1, null, null);
        insertSuperviseReport(
                T3_ALREADY_ARCHIVED, "2020-05", 92005L, 1, now.minusDays(1), FIXED_ARCHIVED_AT);
        assertThat(countIn("supervise_report", T3_LO, T3_HI)).isEqualTo(5);

        int archived = superviseReportArchiveTask.archiveExpiredReports();

        assertThat(archived).as("仅 2 条未归档过期行命中").isEqualTo(2);
        assertThat(archivedAtOf(T3_HIT_A)).as("命中行写 archived_at").isNotNull();
        assertThat(archivedAtOf(T3_HIT_B)).as("命中行写 archived_at").isNotNull();
        assertThat(countIn("supervise_report", T3_LO, T3_HI)).as("归档是封存不是删除：行全部保留").isEqualTo(5);
        assertThat(tinyIntOf("supervise_report", "status", T3_HIT_A))
                .as("归档不改 status（上报状态）")
                .isEqualTo(1);
        assertThat(archivedAtOf(T3_FUTURE)).as("未到期不归档").isNull();
        assertThat(archivedAtOf(T3_RETAIN_NULL)).as("retain_until IS NULL 不归档").isNull();
        assertThat(archivedAtOf(T3_ALREADY_ARCHIVED))
                .as("已归档行的 archived_at 不被重置")
                .isEqualTo(FIXED_ARCHIVED_AT);

        int again = superviseReportArchiveTask.archiveExpiredReports();
        assertThat(again).as("幂等：第二次无到期监管上报").isZero();
    }

    // ------------------------------------------------------------------ T4 · D-5④ 亲情绑定

    @Test
    @DisplayName("T4 / D-5④：到期生效绑定 status 2→4，绝不触碰 deleted / active_uk；反例不变；幂等")
    void t4D5FamilyBindExpireFlipsStatusOnlyNeverTouchesDeleted() {
        LocalDateTime now = LocalDateTime.now();
        for (long id : T4_HITS) {
            insertFamilyBind(id, 2, 0, now.minusDays(1));
        }
        insertFamilyBind(T4_AUTH_NULL, 2, 0, null);
        insertFamilyBind(T4_FUTURE, 2, 0, now.plusDays(30));
        insertFamilyBind(T4_STATUS4, 4, 0, now.minusDays(1));
        insertFamilyBind(T4_STATUS0, 0, 0, now.minusDays(1));
        insertFamilyBind(T4_STATUS3, 3, 0, now.minusDays(1));
        insertFamilyBind(T4_STATUS1, 1, 0, now.minusDays(1));
        insertFamilyBind(T4_DELETED1, 2, 1, now.minusDays(1));
        assertThat(countIn("elder_family_bind", T4_LO, T4_HI)).isEqualTo(10);

        int expired = familyBindingExpireTask.expireExpiredBindings();

        assertThat(expired).as("仅 3 条到期生效行命中").isEqualTo(3);
        for (long id : T4_HITS) {
            assertThat(tinyIntOf("elder_family_bind", "status", id))
                    .as("命中行 status 流转为 4")
                    .isEqualTo(4);
            assertThat(tinyIntOf("elder_family_bind", "deleted", id)).as("★绝不改 deleted").isZero();
            assertThat(activeUkOf(id)).as("★ active_uk 未被释放（唯一键让位语义保持）").isEqualTo(1);
        }
        assertFamilyBindUnchanged(T4_AUTH_NULL, 2, 0);
        assertFamilyBindUnchanged(T4_FUTURE, 2, 0);
        assertFamilyBindUnchanged(T4_STATUS4, 4, 0);
        assertFamilyBindUnchanged(T4_STATUS0, 0, 0);
        assertFamilyBindUnchanged(T4_STATUS3, 3, 0);
        assertFamilyBindUnchanged(T4_STATUS1, 1, 0);
        assertFamilyBindUnchanged(T4_DELETED1, 2, 1);

        int again = familyBindingExpireTask.expireExpiredBindings();
        assertThat(again).as("幂等：第二次无到期亲情绑定").isZero();
    }

    // ------------------------------------------------------------------ T6 · NULL 到期语义（行为层）

    @Test
    @DisplayName("T6 / NULL 到期语义：到期时间列为 NULL 的行，链路全跑后不被任何动作处置")
    void t6NullExpiryRowsAreNeverDisposedByAnyAction() {
        // 三张表的「到期时间」列（retain_until ×2 / auth_end_at）均可空；NULL 语义 = 长期有效、永不到期。
        // ①③ 的 SQL 另显式带 `retain_until IS NOT NULL` —— 三值逻辑下 `IS NULL < cutoff` 求值为 UNKNOWN，
        // 该条件在行为层不可区分（摘掉它 T1/T3 仍绿），故其存在性由 T5 静态守卫兜底；T6 守的是**语义**：
        // 无论 SQL 怎么写，到期时间为 NULL 的行都不得被删除 / 封存 / 流转。
        insertThirdPartyWithNullRetain(T6_TP_RETAIN_NULL, "APPLE", 90015L);
        insertSuperviseReport(T6_SR_RETAIN_NULL, "2021-01", 92006L, 1, null, null);
        insertFamilyBind(T6_FB_AUTH_NULL, 2, 0, null);

        // ④ 这一行其余条件全部"可被命中"（status=2 且 deleted=0），唯一拦截它的就是 auth_end_at IS NULL。
        thirdPartyTask.purgeExpiredBindings();
        superviseReportArchiveTask.archiveExpiredReports();
        familyBindingExpireTask.expireExpiredBindings();

        assertThat(rowExists("sys_user_third_party", T6_TP_RETAIN_NULL))
                .as("① retain_until IS NULL ⇒ 永不被物理删除")
                .isTrue();
        assertThat(archivedAtOf(T6_SR_RETAIN_NULL))
                .as("③ retain_until IS NULL ⇒ 永不被封存（archived_at 保持 NULL）")
                .isNull();
        assertThat(tinyIntOf("elder_family_bind", "status", T6_FB_AUTH_NULL))
                .as("④ auth_end_at IS NULL ⇒ status 保持 2（长期有效）")
                .isEqualTo(2);
        assertThat(tinyIntOf("elder_family_bind", "deleted", T6_FB_AUTH_NULL))
                .as("④ 不触碰 deleted（active_uk 语义不变）")
                .isZero();
    }

    // ------------------------------------------------------------------ T5 · SQL 守卫存在性（静态）

    @Test
    @DisplayName("T5 / 静态守卫：5 个 Mapper 手写 SQL 的关键 WHERE 条件不得被摘除")
    void t5SqlGuardsPresentForAllFourMappers() throws Exception {
        assertThat(
                        sqlOf(
                                SysUserThirdPartyMapper.class,
                                "purgeExpired",
                                LocalDateTime.class,
                                int.class))
                .as("ER-14 物理删除必须显式排除 retain_until IS NULL（防御性硬条件）")
                .contains("retain_until IS NOT NULL");
        assertThat(sqlOf(EvalOrderMapper.class, "archiveExpired", LocalDateTime.class, int.class))
                .as("ER-03 只归档 PUBLISHED")
                .contains("status = 'PUBLISHED'");
        assertThat(
                        sqlOf(
                                SuperviseReportMapper.class,
                                "archiveExpired",
                                LocalDateTime.class,
                                int.class))
                .as("D-5③ 幂等标志：只处理未归档行")
                .contains("archived_at IS NULL");
        assertThat(
                        sqlOf(
                                ElderFamilyBindMapper.class,
                                "expireExpired",
                                LocalDateTime.class,
                                int.class))
                .as("D-5④ 只流转未删除行")
                .contains("deleted = 0");
        assertThat(
                        sqlOf(
                                RetentionReferenceMapper.class,
                                "markExpired",
                                LocalDateTime.class,
                                int.class))
                .as(
                        "P2-7 到期标记必须显式排除 expire_at IS NULL —— 该条件在行为层不可区分"
                                + "（`NULL < cutoff` 求值 UNKNOWN 本就排除该行），故只能靠文本守卫兜底"
                                + "（QA 突变 M4 实证：摘掉它门禁不变红）")
                .contains("expire_at IS NOT NULL");
    }

    // ------------------------------------------------------------------ T7 · P2-7 导出任务到期标记

    @Test
    @DisplayName("T7 / P2-7 D1-B：到期导出任务 status → 4（expire_at 非空且早于 cutoff 且 ≠4）；反例不变；幂等")
    void t7OrphanSweepMarksExpiredExportTasks() {
        LocalDateTime now = LocalDateTime.now();
        insertExportTask(T7_HIT_A, 2, now.minusDays(1));
        insertExportTask(T7_HIT_B, 0, now.minusHours(2));
        insertExportTask(T7_FUTURE, 2, now.plusDays(30));
        insertExportTask(T7_EXPIRE_NULL, 2, null);
        insertExportTask(T7_ALREADY_4, 4, now.minusDays(1));
        assertThat(countIn("export_task", T7_LO, T7_HI)).isEqualTo(5);

        long pendingBefore = expiredExportTaskReaper.countExpired(now);
        assertThat(pendingBefore).as("至少含我方 2 条待标记行").isGreaterThanOrEqualTo(2);

        int marked = expiredExportTaskReaper.markExpired(now, 1000);

        assertThat(marked).as("至少命中我方 2 条（其它库内到期行亦一并标记）").isGreaterThanOrEqualTo(2);
        assertThat(exportStatusOf(T7_HIT_A)).as("到期 status=2 → 4").isEqualTo(4);
        assertThat(exportStatusOf(T7_HIT_B)).as("到期 status=0 → 4（任意 ≠4 均标记）").isEqualTo(4);
        assertThat(exportStatusOf(T7_FUTURE)).as("未到期不动").isEqualTo(2);
        assertThat(exportStatusOf(T7_EXPIRE_NULL)).as("expire_at IS NULL ⇒ 永不过期").isEqualTo(2);
        assertThat(exportStatusOf(T7_ALREADY_4)).as("已 status=4 不动").isEqualTo(4);
        assertThat(expiredExportTaskReaper.countExpired(now)).as("标记后无到期未标记行").isZero();

        int again = expiredExportTaskReaper.markExpired(now, 1000);
        assertThat(again).as("幂等：第二次无到期未标记行").isZero();
        assertThat(exportStatusOf(T7_HIT_A)).isEqualTo(4);
    }

    // ------------------------------------------------------------------ T8 · P2-7 报告引用投影

    @Test
    @DisplayName("T8 / P2-7 D2-A：报告在用集合 = deleted=0 的 report_no 推导键 ∪ 非空 pdf_cos_key；★排除 deleted=1")
    void t8OrphanSweepReportReferenceExcludesSoftDeleted() {
        // 9601：deleted=0、pdf_cos_key 为 NULL ⇒ 仅靠 report_no 推导键在用（D2 关键正例）
        insertEvalReport(T8_ACTIVE_NULL_PDF, "IT-P27-9601", null, 0);
        // 9602：deleted=0、pdf_cos_key 与推导键不同 ⇒ 两条都在用（验证并集）
        insertEvalReport(T8_ACTIVE_WITH_PDF, "IT-P27-9602", "report/IT-P27-9602-alt.pdf", 0);
        // 9603：deleted=1、pdf_cos_key 非空 ⇒ 推导键与 cos_key 都**不得**计入（手写 SQL 必须显式 deleted=0）
        insertEvalReport(T8_DELETED, "IT-P27-9603", "report/IT-P27-9603-extra.pdf", 1);

        Set<String> keys = retentionReferencePort.referencedReportKeys();

        assertThat(keys)
                .as("pdf_cos_key 为 NULL 但 report_no 在用 ⇒ 推导键在用（防误删）")
                .contains("report/IT-P27-9601.pdf");
        assertThat(keys).as("推导键在用").contains("report/IT-P27-9602.pdf");
        assertThat(keys).as("非空 pdf_cos_key 在用（并集第二支）").contains("report/IT-P27-9602-alt.pdf");
        assertThat(keys)
                .as("★ 手写 SQL 必须显式写 deleted = 0：软删报告的推导键不得计入在用")
                .doesNotContain("report/IT-P27-9603.pdf");
        assertThat(keys)
                .as("★ 软删报告的 pdf_cos_key 亦不得计入在用")
                .doesNotContain("report/IT-P27-9603-extra.pdf");
    }

    // ------------------------------------------------------------------ T9 · P2-7 导出引用投影

    @Test
    @DisplayName("T9 / P2-7：导出在用集合 = status≠4 的 task_no 推导键 ∪ 非空 file_cos_key；★不含 status=4 行")
    void t9OrphanSweepExportReferenceExcludesExpired() {
        insertExportTask(
                T9_ACTIVE,
                2,
                LocalDateTime.now().plusDays(30),
                "export/EX-IT-P27-" + T9_ACTIVE + "-alt.csv");
        insertExportTask(
                T9_EXPIRED,
                4,
                LocalDateTime.now().minusDays(1),
                "export/EX-IT-P27-" + T9_EXPIRED + "-alt.csv");

        Set<String> keys = retentionReferencePort.referencedExportKeys();

        assertThat(keys).contains("export/EX-IT-P27-" + T9_ACTIVE + ".csv");
        assertThat(keys).contains("export/EX-IT-P27-" + T9_ACTIVE + "-alt.csv");
        assertThat(keys)
                .as("★ status=4（已过期）行的推导键退出在用集合")
                .doesNotContain("export/EX-IT-P27-" + T9_EXPIRED + ".csv");
        assertThat(keys)
                .as("★ status=4 行的 file_cos_key 亦退出在用集合")
                .doesNotContain("export/EX-IT-P27-" + T9_EXPIRED + "-alt.csv");
    }

    // ------------------------------------------------------------------ helpers · 种数据

    private void insertThirdParty(
            long id, String platform, long userId, LocalDateTime retainUntil) {
        jdbc.update(
                "INSERT INTO sys_user_third_party"
                        + " (id, user_id, platform, open_id_hash, bound_at, retain_until, deleted)"
                        + " VALUES (?, ?, ?, ?, ?, ?, 0)",
                id,
                userId,
                platform,
                hash(id),
                Timestamp.valueOf(LocalDateTime.now()),
                Timestamp.valueOf(retainUntil));
    }

    private void insertThirdPartyWithNullRetain(long id, String platform, long userId) {
        jdbc.update(
                "INSERT INTO sys_user_third_party"
                        + " (id, user_id, platform, open_id_hash, bound_at, retain_until, deleted)"
                        + " VALUES (?, ?, ?, ?, ?, NULL, 0)",
                id,
                userId,
                platform,
                hash(id),
                Timestamp.valueOf(LocalDateTime.now()));
    }

    private void insertEvalOrder(long id, String status, LocalDateTime retainUntil) {
        jdbc.update(
                "INSERT INTO eval_order"
                        + " (id, order_no, elder_id, org_id, assessor_id, rule_version_id, status,"
                        + " retain_until, row_version)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0)",
                id,
                "IT-" + id,
                id,
                id,
                id,
                id,
                status,
                Timestamp.valueOf(retainUntil));
    }

    private void insertSuperviseReport(
            long id,
            String reportMonth,
            long orgId,
            int status,
            LocalDateTime retainUntil,
            LocalDateTime archivedAt) {
        jdbc.update(
                "INSERT INTO supervise_report"
                        + " (id, report_month, org_id, payload, status, retain_until, archived_at,"
                        + " idempotent_key)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                reportMonth,
                orgId,
                "{\"k\":\"v\"}",
                status,
                retainUntil == null ? null : Timestamp.valueOf(retainUntil),
                archivedAt == null ? null : Timestamp.valueOf(archivedAt),
                "IT-" + id);
    }

    private void insertFamilyBind(long id, int status, int deleted, LocalDateTime authEndAt) {
        jdbc.update(
                "INSERT INTO elder_family_bind"
                        + " (id, elder_id, family_user_id, relation, verify_method, auth_start_at,"
                        + " auth_end_at, status, deleted)"
                        + " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                id,
                id,
                id,
                "CHILD",
                "SCAN",
                Timestamp.valueOf(LocalDateTime.now().minusDays(2)),
                authEndAt == null ? null : Timestamp.valueOf(authEndAt),
                status,
                deleted);
    }

    private void insertExportTask(long id, int status, LocalDateTime expireAt) {
        insertExportTask(id, status, expireAt, "export/EX-IT-P27-" + id + ".csv");
    }

    private void insertExportTask(long id, int status, LocalDateTime expireAt, String fileCosKey) {
        jdbc.update(
                "INSERT INTO export_task"
                        + " (id, task_no, export_type, format, mask_level, status, file_cos_key,"
                        + " operator_id, expire_at)"
                        + " VALUES (?, ?, 'EVAL_LIST', 'CSV', 1, ?, ?, ?, ?)",
                id,
                "EX-IT-P27-" + id,
                status,
                fileCosKey,
                id,
                expireAt == null ? null : Timestamp.valueOf(expireAt));
    }

    private void insertEvalReport(long id, String reportNo, String pdfCosKey, int deleted) {
        jdbc.update(
                "INSERT INTO eval_report"
                        + " (id, report_no, order_id, elder_id, org_id, grade_code, total_score,"
                        + " pdf_cos_key, verify_hash, status, deleted)"
                        + " VALUES (?, ?, ?, ?, ?, 'L1', 78.00, ?, ?, 1, ?)",
                id,
                reportNo,
                id,
                id,
                id,
                pdfCosKey,
                "a".repeat(64),
                deleted);
    }

    // ------------------------------------------------------------------ helpers · 查询断言

    private long countIn(String table, long lo, long hi) {
        Long n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE id BETWEEN ? AND ?",
                        Long.class,
                        lo,
                        hi);
        return n == null ? 0L : n;
    }

    private boolean rowExists(String table, long id) {
        Long n =
                jdbc.queryForObject(
                        "SELECT COUNT(*) FROM " + table + " WHERE id = ?", Long.class, id);
        return n != null && n > 0;
    }

    /** eval_order.status 为 VARCHAR，取字符串。 */
    private String evalStatusOf(long id) {
        return jdbc.queryForObject("SELECT status FROM eval_order WHERE id = ?", String.class, id);
    }

    /** export_task.status 为 TINYINT，取整数。 */
    private int exportStatusOf(long id) {
        Integer v =
                jdbc.queryForObject(
                        "SELECT status FROM export_task WHERE id = ?", Integer.class, id);
        return v == null ? -1 : v;
    }

    /** TINYINT 列取整数。 */
    private int tinyIntOf(String table, String column, long id) {
        Integer v =
                jdbc.queryForObject(
                        "SELECT " + column + " FROM " + table + " WHERE id = ?", Integer.class, id);
        return v == null ? -1 : v;
    }

    private LocalDateTime archivedAtOf(long id) {
        Timestamp ts =
                jdbc.queryForObject(
                        "SELECT archived_at FROM supervise_report WHERE id = ?",
                        Timestamp.class,
                        id);
        return ts == null ? null : ts.toLocalDateTime();
    }

    /** active_uk 为生成列：deleted=0 → 1，deleted=1 → NULL。 */
    private Integer activeUkOf(long id) {
        return jdbc.queryForObject(
                "SELECT active_uk FROM elder_family_bind WHERE id = ?", Integer.class, id);
    }

    private void assertFamilyBindUnchanged(long id, int expectedStatus, int expectedDeleted) {
        assertThat(tinyIntOf("elder_family_bind", "status", id))
                .as("反例行 id=%d status 不变", id)
                .isEqualTo(expectedStatus);
        assertThat(tinyIntOf("elder_family_bind", "deleted", id))
                .as("反例行 id=%d deleted 不变", id)
                .isEqualTo(expectedDeleted);
    }

    private static String sqlOf(Class<?> mapper, String method, Class<?>... parameterTypes)
            throws Exception {
        Method m = mapper.getMethod(method, parameterTypes);
        Update update = m.getAnnotation(Update.class);
        if (update != null) {
            return String.join(" ", update.value());
        }
        Delete delete = m.getAnnotation(Delete.class);
        if (delete != null) {
            return String.join(" ", delete.value());
        }
        Select select = m.getAnnotation(Select.class);
        if (select != null) {
            return String.join(" ", select.value());
        }
        throw new IllegalStateException("未找到 SQL 注解：" + mapper.getSimpleName() + "#" + method);
    }

    /** 生成 64 字符的 open_id_hash（表列 CHAR(64)，唯一键要求每行不同）。 */
    private static String hash(long id) {
        return String.format("%064d", id);
    }
}
