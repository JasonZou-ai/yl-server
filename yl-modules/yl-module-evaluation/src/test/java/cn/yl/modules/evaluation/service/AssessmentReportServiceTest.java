package cn.yl.modules.evaluation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yl.common.exception.BizException;
import cn.yl.modules.evaluation.domain.entity.EvalOrder;
import cn.yl.modules.evaluation.domain.entity.EvalReport;
import cn.yl.modules.evaluation.domain.entity.GbRuleVersion;
import cn.yl.modules.evaluation.domain.entity.ReportDimensionScore;
import cn.yl.modules.evaluation.dto.EvalReportResponse;
import cn.yl.modules.evaluation.mapper.EvalAnswerMapper;
import cn.yl.modules.evaluation.mapper.EvalItemMapper;
import cn.yl.modules.evaluation.mapper.EvalOrderMapper;
import cn.yl.modules.evaluation.mapper.EvalReportMapper;
import cn.yl.modules.evaluation.mapper.GbRuleVersionMapper;
import cn.yl.modules.evaluation.mapper.ReportDimensionScoreMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.math.BigDecimal;
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
