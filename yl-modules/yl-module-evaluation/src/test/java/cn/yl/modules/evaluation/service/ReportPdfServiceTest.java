package cn.yl.modules.evaluation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yl.api.report.PdfFontLocator;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.evaluation.domain.entity.EvalReport;
import cn.yl.modules.evaluation.dto.EvalReportResponse;
import cn.yl.modules.evaluation.dto.ReportPdfResponse;
import cn.yl.modules.evaluation.mapper.EvalReportMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 报告 PDF 渲染测试（PRD P02 下载 PDF / ADR-0006）。
 *
 * <p>断言产出的确是 PDF（魔数 {@code %PDF-}）且回填了对象键与验真内容； 中文字形断言（2026-10-10
 * 补，red-line-guard-mutation-proof.md v1.2 附则）： 环境有可渲染字体时，抽取文本必须含中文标题——把「能加载」升级成「能正确渲染」，
 * 封死「字体加载成功但渲染成豆腐块/空白」仍全绿的盲区（字体类型不兼容属渲染抛异常， 已被 CI 38047253236/38048586455 间接捕获；本断言补的是另一半）。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ReportPdfServiceTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReportPdfServiceTest.class);

    @TempDir Path tempDir;

    @Mock private EvalReportMapper reportMapper;
    @Mock private AssessmentReportService reportService;
    @Mock private CarePlanService carePlanService;

    private ReportPdfService service;

    @BeforeEach
    void setUp() {
        service =
                new ReportPdfService(
                        reportMapper,
                        reportService,
                        carePlanService,
                        tempDir.toString(),
                        new PdfFontLocator(""),
                        "https://verify.test");
        when(reportService.getReport(anyLong())).thenReturn(view());
        when(reportService.findReport(anyLong())).thenReturn(reportEntity());
        when(reportMapper.selectOne(any())).thenReturn(reportEntity());
        when(carePlanService.get(anyLong()))
                .thenThrow(new BizException(ErrorCode.NOT_FOUND, "照护方案尚未生成"));
    }

    @Test
    void generateWritesPdfAndBackfillsKey() throws Exception {
        ReportPdfResponse resp = service.generate(3001L);

        assertEquals("report/RP-TEST-0001.pdf", resp.getPdfKey());
        assertTrue(resp.getSizeBytes() > 0, "PDF 必须有内容");
        Path file = tempDir.resolve("report").resolve("RP-TEST-0001.pdf");
        assertTrue(Files.isRegularFile(file), "PDF 必须落盘到存储目录");

        byte[] stored = Files.readAllBytes(file);
        assertEquals("%PDF", new String(stored, 0, 4, StandardCharsets.US_ASCII), "文件头必须是 PDF 魔数");

        ArgumentCaptor<EvalReport> captor = ArgumentCaptor.forClass(EvalReport.class);
        verify(reportMapper).updateById(captor.capture());
        assertEquals("report/RP-TEST-0001.pdf", captor.getValue().getPdfCosKey());
        assertTrue(captor.getValue().getQrContent().contains("RP-TEST-0001"));
    }

    @Test
    void readReturnsBytesAndRejectsIllegalReportNo() {
        service.generate(3002L);
        byte[] bytes = service.read("RP-TEST-0001");
        assertTrue(bytes.length > 0);

        // 目录穿越必须被拦下
        assertThrows(BizException.class, () -> service.read("../evil"));
        assertThrows(BizException.class, () -> service.read("RP 001"));
    }

    @Test
    void readThrowsWhenFileMissing() {
        assertThrows(BizException.class, () -> service.read("RP-NOT-EXIST"));
    }

    /**
     * CR-M2-006 回归：<b>报告行对当前登录态不可见时，即使 PDF 文件真实存在也必须拒绝</b>。
     *
     * <p>修复前 {@code read()} 零 DB 查询，直接按 {@code reportNo} 读文件 → 越权下载。此用例把「先查库」这一步钉死： Mapper 反查返回
     * {@code null}（模拟被第三闸行级条件过滤掉）时，不得读到任何字节。
     */
    @Test
    void readFailsClosedWhenReportNotVisible() {
        service.generate(3004L);
        Path file = tempDir.resolve("report").resolve("RP-TEST-0001.pdf");
        assertTrue(Files.isRegularFile(file), "前置：文件确实已落盘（证明拒绝不是「文件不存在」造成的）");

        when(reportMapper.selectOne(any())).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.read("RP-TEST-0001"));
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode(), "不可见必须失败即拒，且与「不存在」同码（不泄露存在性）");
    }

    @Test
    void generatePropagatesMissingReport() {
        when(reportService.getReport(anyLong()))
                .thenThrow(new BizException(ErrorCode.NOT_FOUND, "报告尚未生成"));
        assertThrows(BizException.class, () -> service.generate(3003L));
    }

    /**
     * 中文字形行为断言（v1.2 附则）：环境有可渲染字体时，抽取 PDF 文本必须含中文标题—— 「能加载」升级为「能正确渲染」。fontless 环境降级渲染无中文字形，跳过（CI 的
     * build-and-test / sonarqube 作业已装 fonts-wqy-zenhei，本断言在 CI 上必然真实执行）。
     */
    @Test
    void extractedTextContainsChineseTitleWhenFontRenderable() throws Exception {
        service.generate(3005L);
        Path file = tempDir.resolve("report").resolve("RP-TEST-0001.pdf");

        Map<String, Object> probe = new PdfFontLocator("").probe();
        if (!"ready".equals(probe.get("status"))) {
            // 静默跳过口子显式化（R15 同源口径）：runner 字体丢失时本断言会「消失」而非「变红」，
            // 跳过必须打 WARNING 让它在日志里可见，否则环境退化被假绿掩盖（SMOKE-R1「被跳过却不告警」同类）
            LOGGER.warn("环境无可渲染中文字体（probe={}），中文字形抽取断言被跳过——这是 SKIP 不是 PASS，请核查 runner 字体安装", probe);
        }
        assumeTrue("ready".equals(probe.get("status")), "无可渲染中文字体的环境跳过（降级渲染无中文字形可断言）");

        try (PDDocument doc = PDDocument.load(file.toFile())) {
            String text = new PDFTextStripper().getText(doc);
            assertTrue(text.contains("老年人能力评估报告"), "抽取文本必须含中文标题——字体加载成功却渲染成豆腐块/空白的盲区由此封死");
        }
    }

    private EvalReportResponse view() {
        EvalReportResponse view = new EvalReportResponse();
        view.setReportId(4001L);
        view.setReportNo("RP-TEST-0001");
        view.setOrderId(3001L);
        view.setElderId(201L);
        view.setStatus("1");
        view.setTotalScore(BigDecimal.valueOf(78));
        view.setGradeCode("GRADE_MILD");
        view.setPreliminaryGradeCode("L1");
        view.setPreliminaryLevelName("能力轻度受损（轻度失能）");
        view.setFinalLevelCode("L1");
        view.setFinalLevelName("能力轻度受损（轻度失能）");
        view.setRuleVersion("1.0.0");
        view.setRuleSha("a".repeat(64));
        view.setVerifyHash("b".repeat(64));
        return view;
    }

    private EvalReport reportEntity() {
        EvalReport report = new EvalReport();
        report.setId(4001L);
        report.setReportNo("RP-TEST-0001");
        report.setOrderId(3001L);
        report.setElderId(201L);
        return report;
    }
}
