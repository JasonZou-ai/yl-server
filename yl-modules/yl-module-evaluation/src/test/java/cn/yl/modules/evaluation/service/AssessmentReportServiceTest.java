package cn.yl.modules.evaluation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yl.api.security.LoginUser;
import cn.yl.common.api.PageQuery;
import cn.yl.common.api.PageResult;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.evaluation.domain.entity.EvalOrder;
import cn.yl.modules.evaluation.domain.entity.EvalReport;
import cn.yl.modules.evaluation.domain.entity.GbRuleVersion;
import cn.yl.modules.evaluation.domain.entity.ReportDimensionScore;
import cn.yl.modules.evaluation.dto.EvalReportResponse;
import cn.yl.modules.evaluation.dto.ReportListItem;
import cn.yl.modules.evaluation.mapper.EvalAnswerMapper;
import cn.yl.modules.evaluation.mapper.EvalItemMapper;
import cn.yl.modules.evaluation.mapper.EvalOrderMapper;
import cn.yl.modules.evaluation.mapper.EvalReportMapper;
import cn.yl.modules.evaluation.mapper.GbRuleVersionMapper;
import cn.yl.modules.evaluation.mapper.ReportDimensionScoreMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 评估报告服务测试（C3 / PRD §1.4）：验证报告绑定「初判 + 终判 + 变化原因 + 规则版本」。
 *
 * <p>验收线：报告必须能同时展示初判与终判等级及其变化原因，并绑定判定所用规则版本与内容指纹， 否则事后复算无法追溯。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssessmentReportServiceTest {

    @Mock private EvalOrderMapper orderMapper;
    @Mock private EvalReportMapper reportMapper;
    @Mock private GbRuleVersionMapper ruleVersionMapper;
    @Mock private EvalAnswerMapper answerMapper;
    @Mock private EvalItemMapper itemMapper;
    @Mock private ReportDimensionScoreMapper dimScoreMapper;

    private AssessmentReportService service;

    @BeforeEach
    void setUp() {
        service =
                new AssessmentReportService(
                        orderMapper,
                        reportMapper,
                        ruleVersionMapper,
                        answerMapper,
                        itemMapper,
                        dimScoreMapper,
                        new AssessmentGradingService());
    }

    /** 登录态是线程本地状态，用例间必须清理，否则状态闸判定会串味（D4-R7 M3）。 */
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void bindReportCarriesPreliminaryFinalAndRuleAnchor() {
        Long orderId = 8001L;
        when(orderMapper.selectById(orderId)).thenReturn(submittedOrder(orderId));
        when(reportMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(ruleVersionMapper.selectById(1L)).thenReturn(ruleVersion());

        EvalReportResponse resp = service.bindReport(orderId);

        assertEquals("L0", resp.getPreliminaryGradeCode());
        assertEquals("L1", resp.getFinalLevelCode());
        assertEquals("GRADE_MILD", resp.getGradeCode());
        assertNotNull(resp.getLevelChangeReason());
        assertEquals("1.0.0", resp.getRuleVersion());
        assertEquals("a".repeat(64), resp.getRuleSha());
        assertEquals(64, resp.getVerifyHash().length());

        ArgumentCaptor<EvalReport> captor = ArgumentCaptor.forClass(EvalReport.class);
        verify(reportMapper).insert(captor.capture());
        assertEquals("L1", captor.getValue().getFinalLevelCode());
        assertEquals(1L, captor.getValue().getRuleVersionId());
    }

    @Test
    void bindReportIsIdempotent() {
        Long orderId = 8002L;
        when(orderMapper.selectById(orderId)).thenReturn(submittedOrder(orderId));
        when(ruleVersionMapper.selectById(1L)).thenReturn(ruleVersion());
        when(reportMapper.selectOne(any(QueryWrapper.class))).thenReturn(existingReport(orderId));

        service.bindReport(orderId);
        verify(reportMapper, never()).insert(any());
    }

    @Test
    void bindReportRejectedWhenNotSubmitted() {
        Long orderId = 8003L;
        EvalOrder draft = submittedOrder(orderId);
        draft.setStatus("DRAFT");
        when(orderMapper.selectById(orderId)).thenReturn(draft);

        assertThrows(BizException.class, () -> service.bindReport(orderId));
    }

    /**
     * 雷达图数据源：报告生成须落 4 维度得分，且满分合计 = 规则集 fullScore（90）。
     *
     * <p>满分取自规则集维度定义而非硬编码，故规则集维度口径变更时本例会失败，起到口径漂移告警作用。
     */
    @Test
    void bindReportPersistsFourDimensionScores() {
        Long orderId = 8005L;
        when(orderMapper.selectById(orderId)).thenReturn(submittedOrder(orderId));
        when(reportMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        when(ruleVersionMapper.selectById(1L)).thenReturn(ruleVersion());
        when(answerMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(itemMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());

        service.bindReport(orderId);

        ArgumentCaptor<ReportDimensionScore> captor =
                ArgumentCaptor.forClass(ReportDimensionScore.class);
        verify(dimScoreMapper, times(4)).insert(captor.capture());
        int fullSum = 0;
        for (ReportDimensionScore row : captor.getAllValues()) {
            fullSum += row.getMaxScore().intValue();
        }
        assertEquals(90, fullSum, "4 维度满分合计须等于规则集 fullScore=90");
    }

    @Test
    void getReportThrowsWhenAbsent() {
        when(reportMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        assertThrows(BizException.class, () -> service.getReport(8004L));
    }

    // ---------------- D4-R7 状态闸（开卡 §2.3 / 验收 §4.2 M3） ----------------

    /**
     * 正向可达（R5 防欠权）：老人读<b>本人已发布</b>报告须成功。
     *
     * <p>只测「越权被拒」不够——若把状态闸写成无条件拒绝，越权断言依然全绿，但老人永远读不到自己的报告。
     */
    @Test
    void elderReadsOwnPublishedReport() {
        loginAs(900000000000000001L, 1, List.of("ELDER"));
        when(reportMapper.selectOne(any(QueryWrapper.class))).thenReturn(existingReport(8101L));
        when(orderMapper.selectById(8101L)).thenReturn(submittedOrder(8101L));

        EvalReportResponse resp = service.getReport(8101L);

        assertEquals("RP-EXISTING", resp.getReportNo());
    }

    /** M3-a：status=0（生成中）本人报告对老人不可读。 */
    @Test
    void elderCannotReadGeneratingReport() {
        loginAs(900000000000000001L, 1, List.of("ELDER"));
        EvalReport generating = existingReport(8102L);
        generating.setStatus(0);
        when(reportMapper.selectOne(any(QueryWrapper.class))).thenReturn(generating);

        BizException ex = assertThrows(BizException.class, () -> service.getReport(8102L));

        assertEquals(
                ErrorCode.NOT_FOUND.getCode(),
                ex.getCode(),
                "生成中报告对老人须不可读；且与「他人报告」同码 10004，不泄露存在性");
    }

    /** M3-b：status=2（已作废）本人报告对老人不可读（PRD P02：作废报告不得再展示等级卡片）。 */
    @Test
    void elderCannotReadVoidReport() {
        loginAs(900000000000000001L, 1, List.of("ELDER"));
        EvalReport voided = existingReport(8103L);
        voided.setStatus(2);
        when(reportMapper.selectOne(any(QueryWrapper.class))).thenReturn(voided);

        BizException ex = assertThrows(BizException.class, () -> service.getReport(8103L));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode(), "已作废报告对老人须不可读");
    }

    /**
     * 反向边界（防过度收紧）：机构管理员<b>必须</b>仍能读 status=2 报告 —— 复核/作废链路依赖该读面。
     *
     * <p>状态闸若写成无条件拒绝，本例立即变红：那是把「收紧老人」误做成「收紧所有人」，属另一方向的欠权。
     */
    @Test
    void orgAdminStillReadsVoidReport() {
        loginAs(900000000000000004L, 2, List.of("ORG_ADMIN"));
        EvalReport voided = existingReport(8104L);
        voided.setStatus(2);
        when(reportMapper.selectOne(any(QueryWrapper.class))).thenReturn(voided);
        when(orderMapper.selectById(8104L)).thenReturn(submittedOrder(8104L));

        assertEquals("RP-EXISTING", service.getReport(8104L).getReportNo());
    }

    // ---------------- rR1a6L B5-后端：本人/本机构报告列表（listReports） ----------------

    /**
     * 他人 {@code elder_id} ⇒ <b>空列表</b>（不是 10004）：归属条件<b>不写在服务层</b>，由第三闸收口 —— {@code eval_report} 在
     * {@code SELF_VIA_PARENT}（父表 {@code elder.user_id}），他人的报告行在 {@code elder_id IN (SELECT id FROM
     * elder WHERE user_id = 我)} 下查不到 ⇒ 本方法只可能返回空列表， 且<b>不得</b>以错误码差异暴露「存在但不可见」。
     *
     * <p>同时钉死「不手写归属/不绕闸」：查询条件里不得出现 {@code elder_id}/{@code org_id}/{@code assessor_id} ——
     * 手写任一个都会绕开第三闸（CR-M2-006 的教训）。
     */
    @Test
    void listReportsLeavesElderScopeToThirdGateAndReturnsEmptyList() {
        loginAs(900000000000000001L, 1, List.of("ELDER"));
        when(reportMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(pageOf());

        PageResult<ReportListItem> result = service.listReports(new PageQuery());

        assertTrue(result.getList().isEmpty(), "他人行被第三闸过滤后必须是空列表（不得 10004/不得报错）");
        assertEquals(0L, result.getTotal());

        QueryWrapper<EvalReport> wrapper = capturedWrapper();
        String sql = wrapper.getSqlSegment();
        assertFalse(sql.contains("elder_id"), "归属必须交给第三闸，服务层不得手写 elder_id 条件：" + sql);
        assertFalse(sql.contains("org_id"), "不得手写 org_id 条件（可见范围不靠服务层区分角色）：" + sql);
        assertFalse(sql.contains("assessor_id"), "不得手写操作者条件：" + sql);
    }

    /** {@code status=2}（VOID）不出现：查询条件硬过滤 {@code status=1}（与 {@code assertPublishedToSelf} 同口径）。 */
    @Test
    void listReportsPinsPublishedOnlyFilter() {
        loginAs(900000000000000001L, 1, List.of("ELDER"));
        when(reportMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(pageOf());

        service.listReports(new PageQuery());

        QueryWrapper<EvalReport> wrapper = capturedWrapper();
        String sql = wrapper.getSqlSegment();
        assertTrue(sql.contains("status"), "必须显式过滤 status：" + sql);
        assertTrue(sql.contains("deleted"), "必须显式过滤 deleted：" + sql);
        assertTrue(sql.contains("published_at DESC"), "排序须按 published_at 倒序：" + sql);
        assertTrue(sql.contains("id DESC"), "排序须以 id 作稳定次序（同秒发布不抖动）：" + sql);

        Collection<Object> params = wrapper.getParamNameValuePairs().values();
        assertTrue(params.contains(1), "status 参数须为 1（已发布/有效）：" + params);
        assertTrue(params.contains(0), "deleted 参数须为 0：" + params);
        assertFalse(params.contains(2), "status=2（VOID）不得进入查询参数（作废报告不得出现在列表）：" + params);
    }

    /**
     * {@code ORG_ADMIN} 结果不受影响（C-4 不回归）：机构域的列表行原样返回，不被「本人域」口径额外削窄。
     *
     * <p>与既有 {@link #orgAdminStillReadsVoidReport()} 互补：那条钉「机构管理员读 status=2 <b>详情</b>仍放行」，
     * 本条钉「列表页不因新增端点而把机构视角收紧」。
     */
    @Test
    void listReportsOrgAdminResultsUnaffected() {
        loginAs(900000000000000004L, 2, List.of("ORG_ADMIN"));
        when(reportMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(pageOf(existingReport(8201L), existingReport(8202L)));

        PageResult<ReportListItem> result = service.listReports(new PageQuery());

        assertEquals(2, result.getList().size(), "机构管理员须拿到机构域内的全部分页行（不得被本人域闸削窄）");
        assertEquals(2L, result.getTotal());
        assertEquals("RP-EXISTING", result.getList().get(0).getReportNo());
    }

    /** 入参缺省与上限：{@code null} → pageNum=1 / pageSize=20；越界值按 {@link PageQuery} 规范收敛。 */
    @Test
    void listReportsNormalizesPaging() {
        loginAs(900000000000000001L, 1, List.of("ELDER"));
        when(reportMapper.selectPage(any(Page.class), any(QueryWrapper.class)))
                .thenReturn(pageOf());

        assertEquals(1, service.listReports(null).getPageNum());
        assertEquals(20, service.listReports(null).getPageSize());

        PageQuery oversized = new PageQuery();
        oversized.setPageNum(0);
        oversized.setPageSize(9999);
        assertEquals(1, service.listReports(oversized).getPageNum());
        assertEquals(100, service.listReports(oversized).getPageSize());
    }

    @SuppressWarnings("unchecked")
    private QueryWrapper<EvalReport> capturedWrapper() {
        ArgumentCaptor<QueryWrapper<EvalReport>> captor =
                ArgumentCaptor.forClass(QueryWrapper.class);
        verify(reportMapper, atLeastOnce()).selectPage(any(Page.class), captor.capture());
        return captor.getValue();
    }

    private Page<EvalReport> pageOf(EvalReport... reports) {
        Page<EvalReport> page = new Page<>(1, 20);
        page.setRecords(List.of(reports));
        page.setTotal(reports.length);
        return page;
    }

    private void loginAs(long userId, int dataScope, List<String> roles) {
        LoginUser user = new LoginUser(userId, 1L, roles, List.of(), List.of(1L), dataScope);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private EvalOrder submittedOrder(Long id) {
        EvalOrder order = new EvalOrder();
        order.setId(id);
        order.setOrderNo("EO-TEST");
        order.setStatus("SUBMITTED");
        order.setElderId(101L);
        order.setOrgId(201L);
        order.setTotalScore(BigDecimal.valueOf(90));
        order.setGradeCode("GRADE_MILD");
        order.setPreliminaryGradeCode("L0");
        order.setPreliminaryLevelName("能力完好");
        order.setFinalLevelCode("L1");
        order.setFinalLevelName("能力轻度受损（轻度失能）");
        order.setGradeChangeReason("确诊痴呆，等级上调一级");
        order.setRuleVersionId(1L);
        order.setRuleSha("a".repeat(64));
        return order;
    }

    private EvalReport existingReport(Long orderId) {
        EvalReport report = new EvalReport();
        report.setId(7001L);
        report.setReportNo("RP-EXISTING");
        report.setOrderId(orderId);
        report.setGradeCode("GRADE_MILD");
        report.setPreliminaryGradeCode("L0");
        report.setFinalLevelCode("L1");
        report.setRuleVersionId(1L);
        report.setRuleSha("a".repeat(64));
        report.setVerifyHash("b".repeat(64));
        report.setStatus(1);
        return report;
    }

    private GbRuleVersion ruleVersion() {
        GbRuleVersion v = new GbRuleVersion();
        v.setId(1L);
        v.setStandardCode("GB/T 42195-2022");
        v.setVersion("1.0.0");
        v.setStatus(1);
        return v;
    }
}
