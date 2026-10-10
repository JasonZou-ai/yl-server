package cn.yl.modules.evaluation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.evaluation.domain.entity.EvalOrder;
import cn.yl.modules.evaluation.domain.entity.EvalReport;
import cn.yl.modules.evaluation.dto.ReportVerifyResponse;
import cn.yl.modules.evaluation.mapper.EvalOrderMapper;
import cn.yl.modules.evaluation.mapper.EvalReportMapper;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * B8 验真服务测试：有效 / 摘要不符 / 不存在（不泄露存在性）/ code 非法 / URL 兼容 / <b>最小返回面</b>。
 *
 * <p>摘要口径与 {@code AssessmentReportService} 逐字段一致（8 字段 {@code |} join），本测试在本地重算以钉死一致性。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportVerifyServiceTest {

    @Mock private EvalReportMapper reportMapper;
    @Mock private EvalOrderMapper orderMapper;

    private ReportVerifyService service;

    @BeforeEach
    void setUp() {
        service = new ReportVerifyService(reportMapper, orderMapper);
    }

    @Test
    void validWhenPublishedAndHashMatches() {
        EvalOrder order = sampleOrder();
        EvalReport report = sampleReport(order, 1);
        report.setVerifyHash(canonicalHash(order));
        when(reportMapper.selectOne(any())).thenReturn(report);
        when(orderMapper.selectById(any())).thenReturn(order);

        ReportVerifyResponse resp = service.verify("RP-ABC123");

        assertTrue(resp.isValid(), "已发布 + 摘要一致 → valid=true");
        assertEquals("GRADE_MILD", resp.getGradeCode());
        assertEquals(report.getPublishedAt(), resp.getPublishedAt());
    }

    @Test
    void invalidWhenHashMismatch() {
        EvalOrder order = sampleOrder();
        EvalReport report = sampleReport(order, 1);
        report.setVerifyHash("deadbeef");
        when(reportMapper.selectOne(any())).thenReturn(report);
        when(orderMapper.selectById(any())).thenReturn(order);

        assertFalse(service.verify("RP-ABC123").isValid(), "摘要不符必须判为无效（防篡改）");
    }

    @Test
    void invalidWhenNotPublished() {
        EvalOrder order = sampleOrder();
        EvalReport report = sampleReport(order, 0);
        report.setVerifyHash(canonicalHash(order));
        when(reportMapper.selectOne(any())).thenReturn(report);
        when(orderMapper.selectById(any())).thenReturn(order);

        assertFalse(service.verify("RP-ABC123").isValid(), "未发布（status=0）→ valid=false");
    }

    /** 未命中不得泄露存在性：valid=false 且不返回等级/时间。 */
    @Test
    void notFoundReturnsInvalidWithoutLeak() {
        when(reportMapper.selectOne(any())).thenReturn(null);

        ReportVerifyResponse resp = service.verify("RP-NOT-EXIST");

        assertFalse(resp.isValid());
        assertNull(resp.getGradeCode());
        assertNull(resp.getPublishedAt());
    }

    @Test
    void verifyAcceptsFullQrUrl() {
        EvalOrder order = sampleOrder();
        EvalReport report = sampleReport(order, 1);
        report.setVerifyHash(canonicalHash(order));
        when(reportMapper.selectOne(any())).thenReturn(report);
        when(orderMapper.selectById(any())).thenReturn(order);

        ReportVerifyResponse resp =
                service.verify("https://yl.example.com/verify?no=RP-ABC123&hash=xyz");

        assertTrue(resp.isValid(), "完整 QR URL 应能解析出 reportNo");
    }

    @Test
    void verifyRejectsIllegalCode() {
        BizException ex = assertThrows(BizException.class, () -> service.verify("bad code!"));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    @Test
    void verifyRejectsBlankCode() {
        BizException ex = assertThrows(BizException.class, () -> service.verify("  "));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    /** 裁决 4：最小返回面 —— 响应仅 {@code valid/gradeCode/publishedAt} 三字段。 */
    @Test
    void responseExposesOnlyThreeFields() {
        Set<String> fields =
                Arrays.stream(ReportVerifyResponse.class.getDeclaredFields())
                        .filter(f -> !Modifier.isStatic(f.getModifiers()))
                        .map(Field::getName)
                        .collect(Collectors.toSet());

        assertEquals(Set.of("valid", "gradeCode", "publishedAt"), fields);
    }

    // ---------------- 夹具 ----------------

    private EvalOrder sampleOrder() {
        EvalOrder order = new EvalOrder();
        order.setId(8001L);
        order.setOrderNo("EO-2026-0001");
        order.setTotalScore(new BigDecimal("66.00"));
        order.setGradeCode("GRADE_MILD");
        order.setPreliminaryGradeCode("L1");
        order.setFinalLevelCode("L2");
        order.setGradeChangeReason("痴呆规则上调");
        order.setRuleVersionId(1L);
        order.setRuleSha("abc123sha");
        return order;
    }

    private EvalReport sampleReport(EvalOrder order, int status) {
        EvalReport report = new EvalReport();
        report.setId(9001L);
        report.setReportNo("RP-ABC123");
        report.setOrderId(order.getId());
        report.setGradeCode(order.getGradeCode());
        report.setStatus(status);
        report.setPublishedAt(LocalDateTime.of(2026, 9, 20, 10, 0));
        return report;
    }

    private String canonicalHash(EvalOrder order) {
        try {
            String content =
                    String.join(
                            "|",
                            String.valueOf(order.getOrderNo()),
                            String.valueOf(order.getTotalScore()),
                            String.valueOf(order.getGradeCode()),
                            String.valueOf(order.getPreliminaryGradeCode()),
                            String.valueOf(order.getFinalLevelCode()),
                            String.valueOf(order.getGradeChangeReason()),
                            String.valueOf(order.getRuleVersionId()),
                            String.valueOf(order.getRuleSha()));
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
