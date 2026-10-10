package cn.yl.modules.report.service;

import cn.yl.api.security.LoginUser;
import cn.yl.api.storage.ObjectKeyResolver;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.common.security.MaskUtils;
import cn.yl.common.security.SensitiveFieldCodec;
import cn.yl.modules.report.domain.entity.ExportTask;
import cn.yl.modules.report.dto.ExportCreateRequest;
import cn.yl.modules.report.dto.ExportSourceRow;
import cn.yl.modules.report.dto.ExportTaskView;
import cn.yl.modules.report.mapper.ExportSourceMapper;
import cn.yl.modules.report.mapper.ExportTaskMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * B9 导出编排服务（CSV 同步导出，B9 全链：校验 → 取数 → CSV → 落盘 → 落库）。
 *
 * <p><b>裁决 2（仅 CSV）</b>：{@code format}/{@code exportType} 越界（XLSX/PDF/REPORT/STAT）一律 {@link
 * ErrorCode#PARAM_INVALID}（10001）并登记残留。
 *
 * <p><b>存储键名口径</b>：{@code export/<taskNo>.csv}（与 {@code report/<reportNo>.pdf} 同形态；后续接 COS 只换写入实现）。
 * 本地目录 {@code yl.export.dir}，未配置回退 {@code ${java.io.tmpdir}/yl-exports}；{@code resolve()} 防目录穿越。
 *
 * <p><b>边界</b>：{@code expire_at} <b>只写值</b>（清理语义整体排 P2-7）；不新增任何 {@code @Scheduled}。
 */
@Slf4j
@Service
public class SuperviseExportService {

    private static final String FORMAT_CSV = "CSV";
    private static final String TYPE_EVAL_LIST = "EVAL_LIST";

    /** 任务完成状态（0-排队 1-生成中 2-完成 3-失败 4-已过期）。 */
    private static final int STATUS_DONE = 2;

    private static final int MASK_LEVEL_STANDARD = 1;
    private static final String FALLBACK_MASK = "***";
    private static final DateTimeFormatter TASK_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final ExportSourceMapper sourceMapper;
    private final ExportTaskMapper exportTaskMapper;
    private final ExportCsvWriter csvWriter;
    private final ObjectMapper objectMapper;
    private final ObjectProvider<SensitiveFieldCodec> codecProvider;
    private final int maxSyncRows;
    private final ObjectKeyResolver keyResolver;
    private final int expireDays;

    /**
     * @param sourceMapper 跨域只读投影 Mapper
     * @param exportTaskMapper 导出任务 Mapper
     * @param csvWriter CSV 渲染器
     * @param objectMapper JSON 序列化（查询快照）
     * @param codecProvider 敏感字段编解码器（条件装配，dev 无密钥时为"未装配"）
     * @param maxSyncRows 单次同步导出上限（超出即 60001）
     * @param exportDir 导出落盘目录（空 → {@code ${java.io.tmpdir}/yl-exports}）
     * @param expireDays {@code expire_at = now + N 天}（只写值）
     */
    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification =
                    "注入的均为 Spring 容器管理的无状态单例 Bean（Mapper/Renderer/ObjectMapper/Codec Provider），"
                            + "构造器注入是标准 DI 用法")
    public SuperviseExportService(
            ExportSourceMapper sourceMapper,
            ExportTaskMapper exportTaskMapper,
            ExportCsvWriter csvWriter,
            ObjectMapper objectMapper,
            ObjectProvider<SensitiveFieldCodec> codecProvider,
            @Value("${yl.export.max-sync-rows:5000}") int maxSyncRows,
            @Value("${yl.export.dir:}") String exportDir,
            @Value("${yl.export.expire-days:7}") int expireDays) {
        this.sourceMapper = sourceMapper;
        this.exportTaskMapper = exportTaskMapper;
        this.csvWriter = csvWriter;
        this.objectMapper = objectMapper;
        this.codecProvider = codecProvider;
        this.maxSyncRows = maxSyncRows;
        this.keyResolver = new ObjectKeyResolver(exportDir, "yl-exports");
        this.expireDays = expireDays;
    }

    /**
     * 创建并同步完成一次 CSV 导出。
     *
     * @param request 导出请求（format/exportType/queryParams）
     * @param operator 登录态操作者（机构/发起人取自此处，请求体不得承载身份）
     * @return 导出任务视图
     */
    public ExportTaskView create(ExportCreateRequest request, LoginUser operator) {
        String format = normalize(request.getFormat());
        String exportType = normalize(request.getExportType());
        if (!FORMAT_CSV.equals(format)) {
            // 裁决 2：XLSX/PDF 一律暂不支持（残留 R2），零新增依赖
            throw new BizException(
                    ErrorCode.PARAM_INVALID, "暂不支持的导出格式（仅 CSV）: " + request.getFormat());
        }
        if (!TYPE_EVAL_LIST.equals(exportType)) {
            throw new BizException(
                    ErrorCode.PARAM_INVALID, "暂不支持的导出类型（仅 EVAL_LIST）: " + request.getExportType());
        }
        if (operator.orgId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "当前账号无机构上下文，无法导出（监管跨机构口径待确认）");
        }

        // 取 maxSyncRows + 1 以探测超限（数据域由第三闸按 eval_order/eval_report/elder 自动收口）
        List<ExportSourceRow> rows = sourceMapper.selectEvalList(maxSyncRows + 1);
        if (rows.size() > maxSyncRows) {
            throw new BizException(ErrorCode.EXPORT_TASK_TOO_LARGE);
        }

        List<String[]> cells = new ArrayList<>(rows.size());
        for (ExportSourceRow row : rows) {
            cells.add(toCells(row));
        }
        byte[] csv = csvWriter.writeCsv(cells);

        String taskNo = generateTaskNo();
        String key = "export/" + taskNo + ".csv";
        write(key, csv);

        LocalDateTime now = LocalDateTime.now();
        ExportTask task = new ExportTask();
        task.setTaskNo(taskNo);
        task.setExportType(exportType);
        task.setFormat(format);
        task.setQueryParams(toJson(request.getQueryParams()));
        task.setMaskLevel(MASK_LEVEL_STANDARD);
        task.setRowCount(rows.size());
        task.setStatus(STATUS_DONE);
        task.setFileCosKey(key);
        task.setOperatorId(operator.userId());
        task.setOrgId(operator.orgId());
        task.setStartedAt(now);
        task.setFinishedAt(now);
        task.setExpireAt(now.plusDays(expireDays));
        exportTaskMapper.insert(task);
        return toView(task);
    }

    private String[] toCells(ExportSourceRow row) {
        return new String[] {
            nullToEmpty(row.getOrderNo()),
            nullToEmpty(row.getGradeCode()),
            maskName(row.getElderNameEnc()),
            nullToEmpty(row.getIdCardMask()),
            maskPhone(row.getPhoneEnc()),
            nullToEmpty(row.getStatus()),
            row.getOrgId() == null ? "" : String.valueOf(row.getOrgId()),
            row.getPublishedAt() == null ? "" : row.getPublishedAt().toString()
        };
    }

    /** 姓名列：解密后脱敏；编解码器未装配（dev 无密钥）或解密失败一律 {@code ***}（绝不明文/原样输出密文）。 */
    private String maskName(String encrypted) {
        String plain = decrypt(encrypted);
        return plain == null ? "" : MaskUtils.maskName(plain);
    }

    /** 手机号列：解密后脱敏；兜底口径同 {@link #maskName(String)}。 */
    private String maskPhone(String encrypted) {
        String plain = decrypt(encrypted);
        return plain == null ? "" : MaskUtils.maskPhone(plain);
    }

    private String decrypt(String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        SensitiveFieldCodec codec = codecProvider.getIfAvailable();
        if (codec == null) {
            return FALLBACK_MASK;
        }
        try {
            return codec.decrypt(encrypted);
        } catch (RuntimeException e) {
            log.warn("导出解密失败，使用兜底脱敏值: {}", e.getMessage());
            return FALLBACK_MASK;
        }
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("导出查询快照序列化失败，忽略: {}", e.getMessage());
            return null;
        }
    }

    private ExportTaskView toView(ExportTask task) {
        ExportTaskView view = new ExportTaskView();
        view.setId(task.getId());
        view.setTaskNo(task.getTaskNo());
        view.setExportType(task.getExportType());
        view.setFormat(task.getFormat());
        view.setRowCount(task.getRowCount());
        view.setStatus(task.getStatus());
        view.setFileCosKey(task.getFileCosKey());
        return view;
    }

    private String generateTaskNo() {
        return "EX"
                + TASK_TS.format(LocalDateTime.now())
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
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
            throw new BizException(ErrorCode.SYSTEM_ERROR, "导出文件落盘失败: " + e.getMessage());
        }
    }

    /** 存储键 → 本地路径（存储根解析与防穿越收敛于 {@link ObjectKeyResolver}，与报告侧同源）。 */
    private Path resolve(String key) {
        return keyResolver.resolve(key);
    }
}
