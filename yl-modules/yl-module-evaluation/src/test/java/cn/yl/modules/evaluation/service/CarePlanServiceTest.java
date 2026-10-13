package cn.yl.modules.evaluation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yl.common.exception.BizException;
import cn.yl.modules.evaluation.domain.entity.CarePlan;
import cn.yl.modules.evaluation.domain.entity.CareTemplate;
import cn.yl.modules.evaluation.domain.entity.EvalReport;
import cn.yl.modules.evaluation.dto.CarePlanItem;
import cn.yl.modules.evaluation.dto.CarePlanResponse;
import cn.yl.modules.evaluation.mapper.CarePlanMapper;
import cn.yl.modules.evaluation.mapper.CareTemplateMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 照护方案测试（PRD P02）：按等级匹配模板、无匹配降级通用建议、评估员建议 → 管理员确认。
 *
 * <p>两条高价值断言：① 无模板时<b>绝不返回空建议</b>（降级通用）；② 确认人必须与建议人不同（双人闭环）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CarePlanServiceTest {

    @Mock private CarePlanMapper planMapper;
    @Mock private CareTemplateMapper templateMapper;
    @Mock private AssessmentReportService reportService;

    private CarePlanService service;

    @BeforeEach
    void setUp() {
        service = new CarePlanService(planMapper, templateMapper, reportService);
        when(reportService.findReport(anyLong())).thenReturn(report("GRADE_MILD"));
    }

    @Test
    void generateMatchesTemplatesByGrade() {
        when(templateMapper.selectList(any(QueryWrapper.class))).thenReturn(templates(2));
        when(planMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        CarePlanResponse resp = service.generate(5001L, 11L);

        assertEquals(2, resp.getItems().size());
        assertEquals("TEMPLATE", resp.getItems().get(0).getSource());
        assertEquals(Integer.valueOf(0), resp.getStatus());
        assertEquals(Long.valueOf(11L), resp.getSuggestBy());
        ArgumentCaptor<CarePlan> captor = ArgumentCaptor.forClass(CarePlan.class);
        verify(planMapper).insert(captor.capture());
        assertEquals("GRADE_MILD", captor.getValue().getGradeCode());
        assertNotNull(captor.getValue().getItemsJson());
    }

    @Test
    void generateFallsBackToGeneralAdviceWhenNoTemplate() {
        when(templateMapper.selectList(any(QueryWrapper.class))).thenReturn(List.of());
        when(planMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        CarePlanResponse resp = service.generate(5002L, 11L);

        assertEquals(3, resp.getItems().size(), "无匹配模板时必须降级通用建议，不得为空");
        for (CarePlanItem item : resp.getItems()) {
            assertEquals("GENERAL", item.getSource());
        }
    }

    @Test
    void generateRequiresReport() {
        when(reportService.findReport(5003L)).thenReturn(null);

        assertThrows(BizException.class, () -> service.generate(5003L, 11L));
    }

    @Test
    void generateIsIdempotent() {
        when(planMapper.selectOne(any(QueryWrapper.class))).thenReturn(existingPlan());

        service.generate(5004L, 11L);

        verify(planMapper, never()).insert(any());
    }

    @Test
    void confirmRequiresDifferentOperator() {
        when(planMapper.selectOne(any(QueryWrapper.class))).thenReturn(existingPlan());

        // 建议人 11 自己确认 → 必须拦下（评估员建议、管理员确认）
        assertThrows(BizException.class, () -> service.confirm(5005L, 11L));
    }

    @Test
    void confirmSetsConfirmedByAdmin() {
        when(planMapper.selectOne(any(QueryWrapper.class))).thenReturn(existingPlan());

        CarePlanResponse resp = service.confirm(5006L, 22L);

        assertEquals(Integer.valueOf(1), resp.getStatus());
        assertEquals(Long.valueOf(22L), resp.getConfirmedBy());
        assertNotNull(resp.getConfirmedAt());
    }

    @Test
    void getThrowsWhenPlanAbsent() {
        when(planMapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        assertThrows(BizException.class, () -> service.get(5007L));
    }

    @Test
    void generateRequiresOperator() {
        assertThrows(BizException.class, () -> service.generate(5008L, null));
    }

    /**
     * W2（CR-M2-009）：老人构造<b>他人</b> orderId 提交建议。
     *
     * <p>第三闸（DataPermissionInterceptor）按 {@code elder.user_id} 反查锚过滤 {@code eval_report}，他人报告在 SQL
     * 改写后不可见 → {@code findReport} 返回 null（本用例模拟该收口结果）。钉两条：① 错误码必须收敛为 {@code
     * NOT_FOUND(10004)}（与「不存在」同码，不泄露存在性），而非 20003/500；② fail-closed——拒绝路径绝不落任何 照护方案行。
     */
    @Test
    void generateCrossElderOrderIsNotFoundAndWritesNothing() {
        when(reportService.findReport(5009L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.generate(5009L, 1001L));

        assertEquals(10004, ex.getCode(), "跨 elder_id 写建议必须收敛为 NOT_FOUND，不泄露存在性");
        verify(planMapper, never()).insert(any(CarePlan.class));
    }

    private EvalReport report(String gradeCode) {
        EvalReport report = new EvalReport();
        report.setId(4001L);
        report.setReportNo("RP-TEST");
        report.setElderId(301L);
        report.setGradeCode(gradeCode);
        return report;
    }

    private List<CareTemplate> templates(int count) {
        List<CareTemplate> list = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            CareTemplate t = new CareTemplate();
            t.setId((long) i);
            t.setGradeCode("GRADE_MILD");
            t.setTitle("建议" + i);
            t.setContent("内容" + i);
            t.setSortNo(i);
            t.setStatus(1);
            list.add(t);
        }
        return list;
    }

    private CarePlan existingPlan() {
        CarePlan plan = new CarePlan();
        plan.setId(3001L);
        plan.setReportId(4001L);
        plan.setElderId(301L);
        plan.setGradeCode("GRADE_MILD");
        plan.setItemsJson("[{\"title\":\"生活协助\",\"content\":\"协助复杂家务\",\"source\":\"TEMPLATE\"}]");
        plan.setSuggestBy(11L);
        plan.setStatus(0);
        return plan;
    }
}
