package cn.yl.modules.supervise.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yl.api.security.LoginUser;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.supervise.domain.entity.SuperviseReport;
import cn.yl.modules.supervise.dto.SuperviseAggregateRow;
import cn.yl.modules.supervise.dto.SuperviseReportView;
import cn.yl.modules.supervise.mapper.SuperviseAggregateMapper;
import cn.yl.modules.supervise.mapper.SuperviseReportMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.YearMonth;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** B10 监管上报服务测试：首次落库（写 retain_until）/ 同月重放更新不新增 / 无机构上下文拒绝。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SuperviseReportServiceTest {

    @Mock private SuperviseReportMapper reportMapper;
    @Mock private SuperviseAggregateMapper aggregateMapper;

    private SuperviseReportService service;

    @BeforeEach
    void setUp() {
        service = new SuperviseReportService(reportMapper, aggregateMapper, new ObjectMapper());
    }

    @Test
    void firstSubmitInsertsWithRetainUntil() {
        when(aggregateMapper.aggregate(1L, YearMonth.now().toString())).thenReturn(aggregate());
        when(reportMapper.selectOne(any())).thenReturn(null);

        SuperviseReportView view = service.submit(operator(1L));

        ArgumentCaptor<SuperviseReport> captor = ArgumentCaptor.forClass(SuperviseReport.class);
        verify(reportMapper).insert(captor.capture());
        SuperviseReport saved = captor.getValue();
        assertEquals(1L, saved.getOrgId());
        assertEquals(YearMonth.now().toString(), saved.getReportMonth());
        assertEquals("SR-1-" + YearMonth.now(), saved.getIdempotentKey());
        assertEquals(1, saved.getStatus());
        assertNotNull(saved.getRetainUntil(), "retain_until 必须写入值（裁决 3：只写值）");
        assertTrue(saved.getPayload().contains("assessmentCount"), saved.getPayload());
        assertEquals(YearMonth.now().toString(), view.getReportMonth());
    }

    @Test
    void sameMonthReplayUpdatesWithoutInsert() {
        SuperviseReport existing = new SuperviseReport();
        existing.setId(9001L);
        existing.setReportMonth(YearMonth.now().toString());
        existing.setOrgId(1L);
        when(aggregateMapper.aggregate(1L, YearMonth.now().toString())).thenReturn(aggregate());
        when(reportMapper.selectOne(any())).thenReturn(existing);

        service.submit(operator(1L));

        verify(reportMapper).updateById(existing);
        verify(reportMapper, never()).insert(any());
        assertNotNull(existing.getRetainUntil());
    }

    @Test
    void rejectsWhenOperatorHasNoOrg() {
        BizException ex = assertThrows(BizException.class, () -> service.submit(operator(null)));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    private LoginUser operator(Long orgId) {
        return new LoginUser(
                101L,
                orgId,
                List.of("ORG_ADMIN"),
                List.of("supervise:report:submit"),
                List.of(1L),
                2);
    }

    private SuperviseAggregateRow aggregate() {
        SuperviseAggregateRow row = new SuperviseAggregateRow();
        row.setAssessmentCount(10L);
        row.setPublishedCount(8L);
        row.setGradeIntactCount(3L);
        row.setGradeSevereCount(2L);
        return row;
    }
}
