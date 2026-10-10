package cn.yl.modules.evaluation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yl.common.exception.BizException;
import cn.yl.modules.evaluation.domain.RetentionPolicy;
import cn.yl.modules.evaluation.domain.entity.EvalOrder;
import cn.yl.modules.evaluation.domain.entity.EvalReport;
import cn.yl.modules.evaluation.domain.entity.EvalReviewLog;
import cn.yl.modules.evaluation.domain.entity.GbRuleVersion;
import cn.yl.modules.evaluation.dto.AssessmentResultResponse;
import cn.yl.modules.evaluation.dto.ReviewLogResponse;
import cn.yl.modules.evaluation.mapper.EvalOrderMapper;
import cn.yl.modules.evaluation.mapper.EvalReviewLogMapper;
import cn.yl.modules.evaluation.mapper.GbRuleVersionMapper;
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
 * 复核状态机测试（PRD §4.4）：SUBMITTED → PUBLISHED / REJECTED → VOID。
 *
 * <p>重点固定三条硬约束：① 录入与复核人互斥；② 驳回必须留意见；③ 发布必须有报告。 任一约束被绕过都会造成「自己录自己复核」的合规缺口。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class AssessmentReviewServiceTest {

    @Mock private EvalOrderMapper orderMapper;
    @Mock private EvalReviewLogMapper logMapper;
    @Mock private GbRuleVersionMapper ruleVersionMapper;
    @Mock private AssessmentReportService reportService;

    private AssessmentReviewService service;

    @BeforeEach
    void setUp() {
        service =
                new AssessmentReviewService(
                        orderMapper, logMapper, ruleVersionMapper, reportService);
        when(ruleVersionMapper.selectById(1L)).thenReturn(ruleVersion());
        when(reportService.findReport(anyLong())).thenReturn(report());
    }

    @Test
    void publishTransitionsToPublishedAndPublishesReport() {
        when(orderMapper.selectById(7001L)).thenReturn(submittedOrder(7001L));

        AssessmentResultResponse resp = service.publish(7001L, 100L, "同意发布");

        assertEquals("PUBLISHED", resp.getStatus());
        ArgumentCaptor<EvalOrder> captor = ArgumentCaptor.forClass(EvalOrder.class);
        verify(orderMapper).updateById(captor.capture());
        assertEquals("PUBLISHED", captor.getValue().getStatus());
        assertEquals(Long.valueOf(100L), captor.getValue().getReviewerId());
        assertNotNull(captor.getValue().getPublishedAt());
        verify(reportService).markPublished(eq(7001L), any());
        assertEquals("PUBLISH", capturedLogAction());
    }

    @Test
    void publishRejectedWhenReviewerIsAssessor() {
        when(orderMapper.selectById(7002L)).thenReturn(submittedOrder(7002L));

        // 录入人 id=9，复核人同为 9 → 互斥校验必须拦下
        assertThrows(BizException.class, () -> service.publish(7002L, 9L, "自审"));
    }

    @Test
    void publishRejectedWhenReportMissing() {
        when(orderMapper.selectById(7003L)).thenReturn(submittedOrder(7003L));
        when(reportService.findReport(7003L)).thenReturn(null);

        assertThrows(BizException.class, () -> service.publish(7003L, 100L, null));
    }

    @Test
    void publishRejectedWhenNotSubmitted() {
        EvalOrder order = submittedOrder(7004L);
        order.setStatus("PUBLISHED");
        when(orderMapper.selectById(7004L)).thenReturn(order);

        assertThrows(BizException.class, () -> service.publish(7004L, 100L, null));
    }

    @Test
    void rejectRequiresOpinion() {
        when(orderMapper.selectById(7005L)).thenReturn(submittedOrder(7005L));

        assertThrows(BizException.class, () -> service.reject(7005L, 100L, "  "));
    }

    @Test
    void rejectDiscardsStaleReport() {
        when(orderMapper.selectById(7006L)).thenReturn(submittedOrder(7006L));

        AssessmentResultResponse resp = service.reject(7006L, 100L, "第 12 项留证缺失，请补充");

        assertEquals("REJECTED", resp.getStatus());
        // 驳回后旧报告必须失效：重提会重新判定，避免旧报告被发布
        verify(reportService).discardReport(7006L);
        assertEquals("REJECT", capturedLogAction());
    }

    @Test
    void voidTransitionsToVoidAndVoidsReport() {
        EvalOrder order = submittedOrder(7007L);
        order.setStatus("PUBLISHED");
        when(orderMapper.selectById(7007L)).thenReturn(order);

        AssessmentResultResponse resp = service.voidOrder(7007L, 100L, "评估对象已去世，作废");

        assertEquals("VOID", resp.getStatus());
        verify(reportService).markVoid(7007L);
        assertEquals("VOID", capturedLogAction());
    }

    @Test
    void voidRejectedWhenDraft() {
        EvalOrder order = submittedOrder(7008L);
        order.setStatus("DRAFT");
        when(orderMapper.selectById(7008L)).thenReturn(order);

        assertThrows(BizException.class, () -> service.voidOrder(7008L, 100L, "作废"));
    }

    @Test
    void logsReturnsAuditChainInOrder() {
        when(logMapper.selectList(any(QueryWrapper.class))).thenReturn(twoLogs());

        List<ReviewLogResponse> logs = service.logs(7009L);

        assertEquals(2, logs.size());
        assertEquals("SUBMIT", logs.get(0).getAction());
        assertEquals("PUBLISH", logs.get(1).getAction());
    }

    /**
     * CR-M2-002：复核人与审计链操作人**必须**是传入的登录态用户，且不受任何请求体字段影响。
     *
     * <p>改造前该值取自请求体 {@code operatorId}（"传他人 ID 即冒用操作者"）；改造后服务签名只接受显式 {@code long
     * operatorId}，身份来源在编译期即被固定为登录态，本用例固定"结论值与审计留痕同源"。
     */
    @Test
    void reviewerAndAuditOperatorComeFromLoginUser() {
        when(orderMapper.selectById(7010L)).thenReturn(submittedOrder(7010L));

        service.publish(7010L, 900000000000000002L, "同意发布");

        ArgumentCaptor<EvalOrder> orderCaptor = ArgumentCaptor.forClass(EvalOrder.class);
        verify(orderMapper).updateById(orderCaptor.capture());
        assertEquals(Long.valueOf(900000000000000002L), orderCaptor.getValue().getReviewerId());

        EvalReviewLog log = capturedLog();
        assertEquals(Long.valueOf(900000000000000002L), log.getOperatorId(), "审计链操作人须为登录用户");
        assertEquals("PUBLISH", log.getAction());
    }

    /**
     * 发布必须写 {@code retain_until}（ER-03：评估记录 5 年）。
     *
     * <p>这是归档链路（{@code ARCHIVED}）的<b>唯一写入口</b>：不写则归档任务的 {@code retain_until IS NOT NULL} 条件永不命中，
     * 表现为"归档功能上线了但一单都不会归档"的静默缺陷（无异常、无日志）。
     */
    @Test
    void publishWritesRetainUntilPerRetentionPolicy() {
        when(orderMapper.selectById(7011L)).thenReturn(submittedOrder(7011L));

        service.publish(7011L, 100L, "同意发布");

        ArgumentCaptor<EvalOrder> captor = ArgumentCaptor.forClass(EvalOrder.class);
        verify(orderMapper).updateById(captor.capture());
        EvalOrder saved = captor.getValue();
        assertNotNull(saved.getRetainUntil(), "发布必须写保留期，否则超期归档永不命中");
        assertEquals(
                saved.getPublishedAt().plusYears(RetentionPolicy.RECORD_RETENTION_YEARS),
                saved.getRetainUntil(),
                "保留期起点应为发布时点、时长取 RetentionPolicy 口径");
    }

    private String capturedLogAction() {
        return capturedLog().getAction();
    }

    private EvalReviewLog capturedLog() {
        ArgumentCaptor<EvalReviewLog> captor = ArgumentCaptor.forClass(EvalReviewLog.class);
        verify(logMapper).insert(captor.capture());
        return captor.getValue();
    }

    private EvalOrder submittedOrder(Long id) {
        EvalOrder order = new EvalOrder();
        order.setId(id);
        order.setOrderNo("EO-TEST");
        order.setStatus("SUBMITTED");
        order.setAssessorId(9L);
        order.setRuleVersionId(1L);
        order.setGradeCode("GRADE_MILD");
        return order;
    }

    private EvalReport report() {
        EvalReport report = new EvalReport();
        report.setId(6001L);
        report.setReportNo("RP-TEST");
        report.setStatus(0);
        return report;
    }

    private GbRuleVersion ruleVersion() {
        GbRuleVersion v = new GbRuleVersion();
        v.setId(1L);
        v.setStandardCode("GB/T 42195-2022");
        v.setVersion("1.0.0");
        return v;
    }

    private List<EvalReviewLog> twoLogs() {
        List<EvalReviewLog> logs = new ArrayList<>();
        EvalReviewLog submit = new EvalReviewLog();
        submit.setAction("SUBMIT");
        submit.setFromStatus("DRAFT");
        submit.setToStatus("SUBMITTED");
        logs.add(submit);
        EvalReviewLog publish = new EvalReviewLog();
        publish.setAction("PUBLISH");
        publish.setFromStatus("SUBMITTED");
        publish.setToStatus("PUBLISHED");
        logs.add(publish);
        return logs;
    }
}
