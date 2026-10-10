package cn.yl.modules.archive.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import cn.yl.common.api.PageResult;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.archive.domain.entity.ElderFamilyBind;
import cn.yl.modules.archive.dto.CarePlanRow;
import cn.yl.modules.archive.dto.DimensionScoreRow;
import cn.yl.modules.archive.dto.FamilyCarePlanView;
import cn.yl.modules.archive.dto.FamilyReportDetailView;
import cn.yl.modules.archive.dto.FamilyReportView;
import cn.yl.modules.archive.dto.FamilyTrendPointView;
import cn.yl.modules.archive.dto.ReportSourceRow;
import cn.yl.modules.archive.mapper.ElderFamilyBindMapper;
import cn.yl.modules.archive.mapper.FamilyCarePlanSourceMapper;
import cn.yl.modules.archive.mapper.FamilyReportSourceMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 家属读链路服务测试（D-4，设计 §2.2 / §4 / §5.5）。
 *
 * <p>断言口径：① {@code relationId} 收口（非本人 / 未生效 / 过期 → 同码 {@code 10004}）；② 仅 {@code
 * eval_report.status=1} （已发布）与 {@code care_plan.status=1}（已确认）可读；③ {@code report.elder_id ==
 * binding.elder_id}；④ {@code items_json} → 字符串列表，空 / 异常 → 空列表（<b>不造数</b>）；⑤ 无已确认方案 → 空方案（<b>非
 * 10004</b>）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FamilyReportQueryServiceTest {

    private static final long RELATION_ID = 700000000000000001L;
    private static final long ELDER_ID = 500L;

    @Mock private ElderFamilyBindMapper bindMapper;
    @Mock private FamilyReportSourceMapper reportMapper;
    @Mock private FamilyCarePlanSourceMapper carePlanMapper;

    private FamilyReportQueryService service;

    @BeforeEach
    void setUp() {
        service = new FamilyReportQueryService(bindMapper, reportMapper, carePlanMapper);
    }

    // ---------------- relationId 收口（同码 10004） ----------------

    @Test
    void requireBindingMissingThrowsNotFound() {
        when(bindMapper.selectById(anyLong())).thenReturn(null);

        BizException ex =
                assertThrows(BizException.class, () -> service.reports(RELATION_ID, null));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void requireBindingNotActiveThrowsNotFound() {
        when(bindMapper.selectById(anyLong())).thenReturn(bind(1, null));

        BizException ex = assertThrows(BizException.class, () -> service.trend(RELATION_ID));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void requireBindingExpiredThrowsNotFound() {
        ElderFamilyBind expired = bind(2, LocalDateTime.now().minusDays(1));
        when(bindMapper.selectById(anyLong())).thenReturn(expired);

        BizException ex = assertThrows(BizException.class, () -> service.carePlan(RELATION_ID));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void requireBindingSoftDeletedThrowsNotFound() {
        ElderFamilyBind deleted = bind(2, null);
        deleted.setDeleted(1);
        when(bindMapper.selectById(anyLong())).thenReturn(deleted);

        BizException ex = assertThrows(BizException.class, () -> service.trend(RELATION_ID));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    // ---------------- 报告列表 ----------------

    @Test
    void reportsMapsPublishedRows() {
        when(bindMapper.selectById(anyLong())).thenReturn(bind(2, null));
        Page<ReportSourceRow> page = new Page<>(1, 10);
        page.setRecords(List.of(reportRow(11L, 1, null, null)));
        page.setTotal(1L);
        when(reportMapper.selectPublishedPage(any(), anyLong())).thenReturn(page);

        PageResult<FamilyReportView> result = service.reports(RELATION_ID, null);

        assertEquals(1L, result.getTotal());
        assertEquals(11L, result.getList().get(0).getReportId());
        assertEquals("GRADE_MILD", result.getList().get(0).getGradeCode());
        assertEquals(ELDER_ID, result.getList().get(0).getElderId());
    }

    // ---------------- 报告详情（同码越界 + 组装） ----------------

    @Test
    void detailRejectsMissingReport() {
        when(bindMapper.selectById(anyLong())).thenReturn(bind(2, null));
        when(reportMapper.selectReportById(anyLong())).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.detail(RELATION_ID, 11L));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void detailRejectsUnpublishedReport() {
        when(bindMapper.selectById(anyLong())).thenReturn(bind(2, null));
        when(reportMapper.selectReportById(anyLong())).thenReturn(reportRow(11L, 2, "名称", null));

        BizException ex = assertThrows(BizException.class, () -> service.detail(RELATION_ID, 11L));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void detailRejectsCrossElderReport() {
        when(bindMapper.selectById(anyLong())).thenReturn(bind(2, null));
        ReportSourceRow foreign = reportRow(11L, 1, "名称", null);
        foreign.setElderId(999L);
        when(reportMapper.selectReportById(anyLong())).thenReturn(foreign);

        BizException ex = assertThrows(BizException.class, () -> service.detail(RELATION_ID, 11L));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void detailAssemblesScoresAndCareItems() {
        when(bindMapper.selectById(anyLong())).thenReturn(bind(2, null));
        when(reportMapper.selectReportById(anyLong())).thenReturn(reportRow(11L, 1, "中度失能", null));
        DimensionScoreRow score = new DimensionScoreRow();
        score.setDimensionCode(1);
        score.setDimensionName("自理能力");
        when(reportMapper.selectDimensionScores(anyLong())).thenReturn(List.of(score));
        when(carePlanMapper.selectConfirmedPlan(anyLong()))
                .thenReturn(
                        plan("[{\"title\":\"防跌倒\",\"content\":\"加装扶手\",\"source\":\"TEMPLATE\"}]"));

        FamilyReportDetailView view = service.detail(RELATION_ID, 11L);

        assertEquals("中度失能", view.getLevelName());
        assertEquals(1, view.getDimensionScores().size());
        assertEquals(List.of("防跌倒：加装扶手"), view.getCareItems());
    }

    @Test
    void detailFallsBackToPreliminaryLevelName() {
        when(bindMapper.selectById(anyLong())).thenReturn(bind(2, null));
        ReportSourceRow row = reportRow(11L, 1, null, "初判名");
        when(reportMapper.selectReportById(anyLong())).thenReturn(row);
        when(reportMapper.selectDimensionScores(anyLong())).thenReturn(List.of());
        when(carePlanMapper.selectConfirmedPlan(anyLong())).thenReturn(null);

        FamilyReportDetailView view = service.detail(RELATION_ID, 11L);

        assertEquals("初判名", view.getLevelName());
        assertTrue(view.getCareItems().isEmpty());
    }

    // ---------------- 趋势 ----------------

    @Test
    void trendReturnsPointsInMapperOrder() {
        when(bindMapper.selectById(anyLong())).thenReturn(bind(2, null));
        ReportSourceRow first = reportRow(1L, 1, "轻度失能", null);
        ReportSourceRow second = reportRow(2L, 1, "中度失能", null);
        when(reportMapper.selectTrendPoints(anyLong())).thenReturn(List.of(first, second));

        List<FamilyTrendPointView> points = service.trend(RELATION_ID);

        assertEquals(2, points.size());
        assertEquals(1L, points.get(0).getReportId());
        assertEquals("轻度失能", points.get(0).getLevelName());
        assertEquals("中度失能", points.get(1).getLevelName());
    }

    // ---------------- 照护方案 ----------------

    @Test
    void carePlanReturnsEmptyWhenNoConfirmedPlan() {
        when(bindMapper.selectById(anyLong())).thenReturn(bind(2, null));
        when(carePlanMapper.selectConfirmedPlan(anyLong())).thenReturn(null);

        FamilyCarePlanView view = service.carePlan(RELATION_ID);

        assertNull(view.getPlanId(), "无已确认方案 → 空方案，非 10004");
        assertEquals(ELDER_ID, view.getElderId());
        assertTrue(view.getItems().isEmpty());
    }

    @Test
    void carePlanParsesItemsJson() {
        when(bindMapper.selectById(anyLong())).thenReturn(bind(2, null));
        when(carePlanMapper.selectConfirmedPlan(anyLong()))
                .thenReturn(
                        plan(
                                "[{\"title\":\"防跌倒\",\"content\":\"加装扶手\",\"source\":\"TEMPLATE\"},"
                                        + "{\"content\":\"规律用药\",\"source\":\"GENERAL\"}]"));

        FamilyCarePlanView view = service.carePlan(RELATION_ID);

        assertEquals(List.of("防跌倒：加装扶手", "规律用药"), view.getItems());
    }

    @Test
    void carePlanToleratesMalformedItemsJson() {
        when(bindMapper.selectById(anyLong())).thenReturn(bind(2, null));
        when(carePlanMapper.selectConfirmedPlan(anyLong())).thenReturn(plan("{not-json"));

        FamilyCarePlanView view = service.carePlan(RELATION_ID);

        assertTrue(view.getItems().isEmpty(), "非法 JSON → 空列表，不抛错、不造数");
    }

    // ---------------- helpers ----------------

    private ElderFamilyBind bind(int status, LocalDateTime authEndAt) {
        ElderFamilyBind bind = new ElderFamilyBind();
        bind.setId(RELATION_ID);
        bind.setElderId(ELDER_ID);
        bind.setFamilyUserId(900000000000000004L);
        bind.setStatus(status);
        bind.setDeleted(0);
        bind.setAuthEndAt(authEndAt);
        return bind;
    }

    private ReportSourceRow reportRow(
            Long reportId, Integer status, String finalLevelName, String preliminaryLevelName) {
        ReportSourceRow row = new ReportSourceRow();
        row.setReportId(reportId);
        row.setReportNo("R" + reportId);
        row.setElderId(ELDER_ID);
        row.setGradeCode("GRADE_MILD");
        row.setTotalScore(new BigDecimal("42.00"));
        row.setPublishedAt(LocalDateTime.now());
        row.setStatus(status);
        row.setFinalLevelName(finalLevelName);
        row.setPreliminaryLevelName(preliminaryLevelName);
        return row;
    }

    private CarePlanRow plan(String itemsJson) {
        CarePlanRow plan = new CarePlanRow();
        plan.setId(88L);
        plan.setReportId(11L);
        plan.setElderId(ELDER_ID);
        plan.setGradeCode("GRADE_MILD");
        plan.setItemsJson(itemsJson);
        plan.setStatus(1);
        plan.setConfirmedAt(LocalDateTime.now());
        return plan;
    }
}
