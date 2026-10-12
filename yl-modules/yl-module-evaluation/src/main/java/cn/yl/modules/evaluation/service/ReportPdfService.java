package cn.yl.modules.evaluation.service;

import cn.yl.api.report.PdfFontLocator;
import cn.yl.api.storage.ObjectKeyResolver;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.evaluation.domain.entity.EvalReport;
import cn.yl.modules.evaluation.dto.CarePlanItem;
import cn.yl.modules.evaluation.dto.DimScoreItem;
import cn.yl.modules.evaluation.dto.EvalReportResponse;
import cn.yl.modules.evaluation.dto.ReportPdfResponse;
import cn.yl.modules.evaluation.mapper.EvalReportMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.apache.fontbox.ttf.TrueTypeCollection;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 报告 PDF 渲染（PRD P02「下载 PDF」＋流程 A ④「生成报告 PDF+二维码」）。
 *
 * <p>选型见 ADR-0006：Apache PDFBox（Apache-2.0）——避开 iText 的 AGPL 传染性；渲染为矢量文本 PDF， 内容取自报告视图与照护方案，不含端上算分。
 *
 * <p><b>中文字体</b>：PDF 内置字体无中文字形，必须嵌入 TTF。字体按「配置 → 系统候选」顺序探测； 未找到时<b>降级渲染</b>（中文占位、{@code
 * chineseEmbedded=false}），不阻断业务——字体缺失属部署问题， 由 {@code yl.report.pdf.font-path} 显式指定即可修复。
 *
 * <p><b>存储</b>：MVP 落本地目录，键名 {@code report/<reportNo>.pdf} 与对象存储形态一致，后续接 COS 只需替换写入实现。
 */
@Service
public class ReportPdfService {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReportPdfService.class);

    private static final float MARGIN = 48f;
    private static final float A4_WIDTH = PDRectangle.A4.getWidth();
    private static final float A4_HEIGHT = PDRectangle.A4.getHeight();
    private static final float TITLE_SIZE = 18f;
    private static final float SECTION_SIZE = 13f;
    private static final float BODY_SIZE = 11f;
    private static final float LINE_HEIGHT = 18f;
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final EvalReportMapper reportMapper;
    private final AssessmentReportService reportService;
    private final CarePlanService carePlanService;
    private final ObjectKeyResolver keyResolver;
    private final PdfFontLocator fontLocator;
    private final String qrBaseUrl;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification =
                    "注入的均为 Spring 容器管理的无状态单例 Bean（Mapper / Service / Locator），构造器注入是标准 DI 用法")
    public ReportPdfService(
            EvalReportMapper reportMapper,
            AssessmentReportService reportService,
            CarePlanService carePlanService,
            @Value("${yl.report.pdf.dir:}") String storageDir,
            PdfFontLocator fontLocator,
            @Value("${yl.report.qr-base-url:https://yl.example.com/verify}") String qrBaseUrl) {
        this.reportMapper = reportMapper;
        this.reportService = reportService;
        this.carePlanService = carePlanService;
        this.keyResolver = new ObjectKeyResolver(storageDir, "yl-reports");
        this.fontLocator = fontLocator;
        this.qrBaseUrl = qrBaseUrl;
    }

    /**
     * 生成报告 PDF 并回填 {@code pdf_cos_key} / {@code qr_content}。
     *
     * @param orderId 评估单 id（须已生成报告）
     * @return PDF 结果（键名、字节数、是否嵌入中文字体）
     */
    public ReportPdfResponse generate(Long orderId) {
        EvalReportResponse view = reportService.getReport(orderId);
        List<CarePlanItem> careItems = loadCareItems(orderId);
        byte[] pdf;
        boolean embedded;
        try {
            PDRendered rendered = render(view, careItems);
            pdf = rendered.bytes();
            embedded = rendered.chineseEmbedded();
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "报告 PDF 渲染失败: " + e.getMessage());
        }
        String key = "report/" + view.getReportNo() + ".pdf";
        write(key, pdf);
        String qrContent =
                qrBaseUrl + "?no=" + view.getReportNo() + "&hash=" + view.getVerifyHash();
        EvalReport report = reportService.findReport(orderId);
        if (report == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "报告不存在: orderId=" + orderId);
        }
        report.setPdfCosKey(key);
        report.setQrContent(qrContent);
        reportMapper.updateById(report);

        ReportPdfResponse resp = new ReportPdfResponse();
        resp.setReportId(view.getReportId());
        resp.setReportNo(view.getReportNo());
        resp.setPdfKey(key);
        resp.setQrContent(qrContent);
        resp.setSizeBytes(pdf.length);
        resp.setChineseEmbedded(embedded);
        return resp;
    }

    /**
     * 按报告编号读取 PDF 字节（下载）。编号做白名单校验，防目录穿越。
     *
     * <p><b>先查库、再读文件（CR-M2-006 修复）</b>：第三闸（数据域）由 MyBatis-Plus 拦截器实现，<b>只作用于 SQL</b>。 原实现「白名单校验 → 直接
     * {@code Files.readAllBytes}」零 DB 查询 → 数据域与报告归属完全失效：任何已登录用户只要知道 {@code
     * reportNo}，即可下载他人（含跨机构）报告全文（姓名 / 等级 / 分项得分 / 照护建议）。
     *
     * <p>故此处<b>先按 {@code report_no} 经 Mapper 反查一次</b>：跨机构 / 跨关系的行会被行级条件过滤掉 → 查不到 → 统一 {@code
     * 404}。且「报告不存在」与「无权访问」返回<b>同一错误</b>，避免通过错误码差异枚举 {@code reportNo}。
     */
    public byte[] read(String reportNo) {
        if (reportNo == null || !reportNo.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new BizException(ErrorCode.PARAM_INVALID, "报告编号非法: " + reportNo);
        }
        requireVisible(reportNo);
        Path file = resolve("report/" + reportNo + ".pdf");
        if (!Files.isRegularFile(file)) {
            throw new BizException(ErrorCode.NOT_FOUND, "PDF 不存在，请先生成: reportNo=" + reportNo);
        }
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "读取 PDF 失败: " + e.getMessage());
        }
    }

    /**
     * 归属可见性校验（CR-M2-006）：经 Mapper 反查 {@code report_no}，把行级可见性交给第三闸（数据域）收口。
     *
     * <p>本方法的存在即是修复要点——<b>只要读取文件前经过一次真实 SQL，数据域拦截器就能介入</b>；反之（原实现）第三闸在
     * 文件路径上完全不生效。查询不到时统一按"不存在"处理，不泄露"存在但无权访问"这一事实。
     */
    private void requireVisible(String reportNo) {
        EvalReport visible =
                reportMapper.selectOne(
                        Wrappers.<EvalReport>lambdaQuery().eq(EvalReport::getReportNo, reportNo));
        if (visible == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "报告不存在或无权访问: reportNo=" + reportNo);
        }
        // D4-R7 状态闸（与报告详情同口径）：老人视角不下载未发布/已作废报告，否则「详情 10004、PDF 能下载」即为绕过。
        reportService.assertPublishedToSelf(visible);
    }

    private List<CarePlanItem> loadCareItems(Long orderId) {
        try {
            return carePlanService.get(orderId).getItems();
        } catch (BizException e) {
            // 照护方案尚未生成时报告仍可导出，章节显示「待生成」
            return List.of();
        }
    }

    /** 渲染结果：PDF 字节 + 是否成功嵌入中文字体。 */
    private record PDRendered(byte[] bytes, boolean chineseEmbedded) {}

    private PDRendered render(EvalReportResponse view, List<CarePlanItem> careItems)
            throws IOException {
        // TTC 集合必须活到 doc.save() 之后（子集化在 save 阶段读取字体数据），save 完成再统一关闭
        List<TrueTypeCollection> openedCollections = new ArrayList<>();
        try (PDDocument doc = new PDDocument()) {
            // 字体候选与加载真源在 PdfFontLocator（TTC 集合引用须活到下方 doc.save() 之后，finally 统一关闭）
            PDFont chinese = fontLocator.loadFirst(doc, openedCollections).orElse(null);
            PDFont font = chinese == null ? PDType1Font.HELVETICA : chinese;
            Cursor cursor = new Cursor(doc, font, chinese != null);
            drawTitle(cursor, view);
            drawGrading(cursor, view);
            drawDimensions(cursor, view);
            drawCarePlan(cursor, careItems);
            drawVerify(cursor, view);
            cursor.close();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            doc.save(out);
            return new PDRendered(out.toByteArray(), chinese != null);
        } finally {
            for (TrueTypeCollection collection : openedCollections) {
                try {
                    collection.close();
                } catch (IOException e) {
                    LOGGER.warn("关闭 PDF 中文字体集合失败（不影响已生成的报告）: {}", e.getMessage());
                }
            }
        }
    }

    private void drawTitle(Cursor cursor, EvalReportResponse view) throws IOException {
        cursor.line("老年人能力评估报告（GB/T 42195-2022）", TITLE_SIZE);
        cursor.line("报告编号：" + view.getReportNo() + "    评估单：" + view.getOrderId(), BODY_SIZE);
        cursor.line(
                "老人档案号：" + view.getElderId() + "    生成时间：" + TS.format(LocalDateTime.now()),
                BODY_SIZE);
        cursor.gap();
    }

    private void drawGrading(Cursor cursor, EvalReportResponse view) throws IOException {
        cursor.line("一、能力等级判定", SECTION_SIZE);
        cursor.line("总分：" + view.getTotalScore() + " / 90", BODY_SIZE);
        cursor.line(
                "初判等级："
                        + view.getPreliminaryLevelName()
                        + "（"
                        + view.getPreliminaryGradeCode()
                        + "）",
                BODY_SIZE);
        cursor.line(
                "终判等级：" + view.getFinalLevelName() + "（" + view.getFinalLevelCode() + "）",
                BODY_SIZE);
        cursor.line(
                "等级变化原因："
                        + (view.getLevelChangeReason() == null
                                ? "无（初判与终判一致）"
                                : view.getLevelChangeReason()),
                BODY_SIZE);
        cursor.gap();
    }

    private void drawDimensions(Cursor cursor, EvalReportResponse view) throws IOException {
        cursor.line("二、维度得分（雷达图数据）", SECTION_SIZE);
        for (DimScoreItem dim : view.getDimScores()) {
            cursor.line(
                    "  "
                            + dim.getDimensionName()
                            + "："
                            + dim.getScore()
                            + " / "
                            + dim.getFullScore()
                            + (dim.getRefuseCount() > 0
                                    ? "（拒答 " + dim.getRefuseCount() + " 项，记 0 分）"
                                    : ""),
                    BODY_SIZE);
        }
        cursor.gap();
    }

    private void drawCarePlan(Cursor cursor, List<CarePlanItem> items) throws IOException {
        cursor.line("三、照护建议", SECTION_SIZE);
        if (items.isEmpty()) {
            cursor.line("  照护方案待生成（需评估员建议、管理员确认后展示）", BODY_SIZE);
            cursor.gap();
            return;
        }
        int idx = 1;
        for (CarePlanItem item : items) {
            cursor.line("  " + idx + ". " + item.getTitle(), BODY_SIZE);
            cursor.line("     " + item.getContent(), BODY_SIZE);
            idx++;
        }
        cursor.gap();
    }

    private void drawVerify(Cursor cursor, EvalReportResponse view) throws IOException {
        cursor.line("四、验真信息", SECTION_SIZE);
        cursor.line("规则版本：" + view.getRuleVersion() + "    规则指纹：" + view.getRuleSha(), BODY_SIZE);
        cursor.line("内容摘要（SHA-256）：" + view.getVerifyHash(), BODY_SIZE);
        cursor.line("扫码验真：" + qrBaseUrl + "?no=" + view.getReportNo(), BODY_SIZE);
    }

    private Path resolve(String key) {
        return keyResolver.resolve(key);
    }

    private void write(String key, byte[] bytes) {
        try {
            Path file = resolve(key);
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.write(file, bytes);
        } catch (IOException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "报告 PDF 落盘失败: " + e.getMessage());
        }
    }

    private static PDPageContentStream openStream(PDDocument doc, PDFont font) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        PDPageContentStream stream = new PDPageContentStream(doc, page);
        stream.setFont(font, BODY_SIZE);
        return stream;
    }

    /** 绘制游标：封装当前页、内容流与纵向位置，负责自动分页与换行。 */
    private static final class Cursor {

        private final PDDocument doc;
        private final PDFont font;
        private final boolean chinese;
        private PDPageContentStream stream;
        private float y;

        Cursor(PDDocument doc, PDFont font, boolean chinese) {
            this.doc = doc;
            this.font = font;
            this.chinese = chinese;
            this.y = A4_HEIGHT - MARGIN;
        }

        void line(String text, float size) throws IOException {
            if (text == null || text.isBlank()) {
                return;
            }
            for (String seg : wrap(text, size)) {
                newLineIfNeeded();
                stream.beginText();
                stream.setFont(font, size);
                stream.newLineAtOffset(MARGIN, y);
                stream.showText(seg);
                stream.endText();
                y -= LINE_HEIGHT;
            }
        }

        void gap() throws IOException {
            newLineIfNeeded();
            y -= LINE_HEIGHT / 2;
        }

        void close() throws IOException {
            if (stream != null) {
                stream.close();
                stream = null;
            }
        }

        private void newLineIfNeeded() throws IOException {
            if (stream == null) {
                stream = openStream(doc, font);
                return;
            }
            if (y <= MARGIN + LINE_HEIGHT) {
                stream.close();
                stream = openStream(doc, font);
                y = A4_HEIGHT - MARGIN;
            }
        }

        /** 按字号估算每行可容纳字符数做硬换行；缺中文字体时整段降级为占位提示。 */
        private List<String> wrap(String text, float size) {
            String safe = chinese || isAscii(text) ? text : "[中文内容缺字体，请配置 yl.report.pdf.font-path]";
            int max = Math.max(8, (int) ((A4_WIDTH - 2 * MARGIN) / (size * 0.62f)));
            List<String> lines = new ArrayList<>();
            for (int i = 0; i < safe.length(); i += max) {
                lines.add(safe.substring(i, Math.min(i + max, safe.length())));
            }
            return lines;
        }

        private boolean isAscii(String text) {
            return text.chars().allMatch(c -> c < 128);
        }
    }
}
