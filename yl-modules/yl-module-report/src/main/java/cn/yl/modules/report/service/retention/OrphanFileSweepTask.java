package cn.yl.modules.report.service.retention;

import cn.yl.api.storage.ObjectKeyResolver;
import cn.yl.modules.report.domain.retention.ExpiredExportTaskReaper;
import cn.yl.modules.report.domain.retention.RetentionReferencePort;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 孤儿文件回收任务（P2-7，保留期链路<b>第 5 个动作</b>）。
 *
 * <p>把「存储根里已无 DB 引用的文件」移入隔离目录（{@code <baseDir>/.trash/<yyyyMMdd>/<前缀>/}，<b>不 {@code rm}</b>），
 * 使「文件」维度与「DB 行」维度享有同一套保留期治理。
 *
 * <h3>动作流程（承设计 §4）</h3>
 *
 * <ol>
 *   <li>取 {@code cutoff = now}；
 *   <li>{@code dry-run} ⇒ 只统计（根 A 孤儿数 / 根 B 孤儿数 / 到期导出任务数）并返回 0（<b>零移动</b>）；
 *   <li><b>DB 先行</b>：按 {@code max-rounds} 限批把到期导出任务置 {@code status = 4}（{@link
 *       ExpiredExportTaskReaper}）；
 *   <li>取两个「在用集合」（此刻 {@code status = 4} 行已不在导出在用集合内）；
 *   <li>扫根 A（{@code report/}）与根 B（{@code export/}），逐个把孤儿文件移入隔离目录；
 *   <li>返回两根实际回收文件数。
 * </ol>
 *
 * <h3>硬约束（红线）</h3>
 *
 * <ul>
 *   <li><b>只列一层</b>：仅枚举 {@code baseDir/<前缀>/} 的直接子项，<b>不做 {@code Files.walk} 递归</b>、<b>不用通配删除</b>；
 *   <li>只处理 {@code Files.isRegularFile(p, NOFOLLOW_LINKS)} ⇒ <b>在支持符号链接的平台</b>符号链接被跳过（防逃逸），目录亦跳过。
 *       <br>
 *       ⚠ <b>平台限制（非缺陷）</b>：Windows 沙箱文件系统不支持真正的符号链接——{@code Files.createSymbolicLink} 与 {@code
 *       mklink} 均<b>「假成功」</b>（不抛异常，却静默降级为 0 字节普通文件；随后 {@code readSymbolicLink} 抛 {@code
 *       NotLinkException}、{@code isSymbolicLink} 恒 {@code false}）。故本机<b>无法构造</b>该输入，此分支由支持符号链接的
 *       Linux CI 覆盖；即便在此降级为普通文件，{@code Files.move} 也只搬移该文件本身、<b>不跟随</b>链接，根外目标不受影响；
 *   <li>移动目标在同一 {@code baseDir} 内（{@code .trash/}）⇒ {@code Files.move} <b>同盘原子</b>、可还原；
 *   <li>单个文件失败只 {@code warn} 并继续（<b>失败不抛</b>，不阻断其它文件）；
 *   <li>整体异常只 {@code error} 并返回 0，下周期自然重试；
 *   <li>{@code .trash/} 不在 {@code report/}、{@code export/} 前缀内 ⇒ <b>不会被二次扫描</b>（天然幂等）。
 * </ul>
 *
 * <h3>调度与依赖</h3>
 *
 * <p>本任务<b>没有自己的 {@code @Scheduled}</b>（触发壳唯一，仍在 {@code yl-bootstrap} 的 {@code
 * RetentionChainScheduler}，ADR-0004）。存储根解析统一走 {@link ObjectKeyResolver}（唯一真源，防"扫的根 ≠ 写的根"静默漂移）。
 *
 * <p>配置：{@code yl.retention.orphan-file.*}（{@code enabled} / {@code batch-size} / {@code
 * max-rounds} / {@code dry-run} / {@code trash-subdir} / {@code export-expire-mark}）；存储根本身<b>复用</b>
 * {@code yl.report.pdf.dir} 与 {@code yl.export.dir}，<b>不新增第二套根配置</b>。
 */
@Slf4j
@Component
@ConditionalOnProperty(
        prefix = "yl.retention.orphan-file",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
public class OrphanFileSweepTask {

    private static final String LOG_PREFIX = "[retention]";

    /** 报告根前缀（键名与 {@code ReportPdfService#generate} 同式）。 */
    private static final String REPORT_PREFIX = "report";

    /** 导出根前缀（键名与 {@code SuperviseExportService#create} 同式）。 */
    private static final String EXPORT_PREFIX = "export";

    private static final DateTimeFormatter TRASH_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final RetentionReferencePort referencePort;

    private final ExpiredExportTaskReaper reaper;

    private final ObjectKeyResolver reportResolver;

    private final ObjectKeyResolver exportResolver;

    private final int batchSize;

    private final int maxRounds;

    private final boolean dryRun;

    private final String trashSubdir;

    private final boolean exportExpireMark;

    /**
     * @param referencePort 引用源端口（两个根的在用键集合）
     * @param reaper 导出任务到期标记端口（D1-B）
     * @param reportPdfDir 报告存储根（复用 {@code yl.report.pdf.dir}；空 → {@code
     *     ${java.io.tmpdir}/yl-reports}）
     * @param exportDir 导出存储根（复用 {@code yl.export.dir}；空 → {@code ${java.io.tmpdir}/yl-exports}）
     * @param batchSize 每轮每根最多回收文件数
     * @param maxRounds 单次触发的最大轮次数，兜底防死循环
     * @param dryRun 干跑：只统计不回收
     * @param trashSubdir 隔离子目录名（相对各存储根）
     * @param exportExpireMark 是否把到期导出任务置 {@code status = 4}
     */
    public OrphanFileSweepTask(
            RetentionReferencePort referencePort,
            ExpiredExportTaskReaper reaper,
            @Value("${yl.report.pdf.dir:}") String reportPdfDir,
            @Value("${yl.export.dir:}") String exportDir,
            @Value("${yl.retention.orphan-file.batch-size:500}") int batchSize,
            @Value("${yl.retention.orphan-file.max-rounds:20}") int maxRounds,
            @Value("${yl.retention.orphan-file.dry-run:false}") boolean dryRun,
            @Value("${yl.retention.orphan-file.trash-subdir:.trash}") String trashSubdir,
            @Value("${yl.retention.orphan-file.export-expire-mark:true}")
                    boolean exportExpireMark) {
        this.referencePort = referencePort;
        this.reaper = reaper;
        this.reportResolver = new ObjectKeyResolver(reportPdfDir, "yl-reports");
        this.exportResolver = new ObjectKeyResolver(exportDir, "yl-exports");
        this.batchSize = batchSize > 0 ? batchSize : 1;
        this.maxRounds = maxRounds > 0 ? maxRounds : 1;
        this.dryRun = dryRun;
        this.trashSubdir = trashSubdir == null || trashSubdir.isBlank() ? ".trash" : trashSubdir;
        this.exportExpireMark = exportExpireMark;
    }

    /**
     * 回收两个存储根内的孤儿文件。
     *
     * <p>本方法为普通业务方法（<b>无</b> {@code @Scheduled}），由 {@code RetentionChainScheduler} 与其余四个动作在同一次调度内
     * 串行调用（本动作为第五环）。
     *
     * @return 两根实际回收文件数（0 表示本轮无孤儿；{@code dry-run} 时恒为 0；异常时返回 0，等待下个周期重试）
     */
    public int sweepOrphanFiles() {
        LocalDateTime cutoff = LocalDateTime.now();
        LocalDate date = cutoff.toLocalDate();
        try {
            if (dryRun) {
                long orphansA =
                        countOrphans(
                                reportResolver,
                                REPORT_PREFIX,
                                referencePort.referencedReportKeys());
                long orphansB =
                        countOrphans(
                                exportResolver,
                                EXPORT_PREFIX,
                                referencePort.referencedExportKeys());
                long expired = reaper.countExpired(cutoff);
                log.info(
                        "{} ORPHAN-SWEEP dry-run：报告根孤儿 {} 个、导出根孤儿 {} 个、到期导出任务 {} 条（cutoff={}），"
                                + "未执行回收",
                        LOG_PREFIX,
                        orphansA,
                        orphansB,
                        expired,
                        cutoff);
                return 0;
            }

            // 【DB 先行】到期导出任务先置 status = 4，随后其键退出在用集合 ⇒ 文件方可回收（D1-B）。
            int marked = 0;
            if (exportExpireMark) {
                marked = markExpiredTasks(cutoff);
            }

            // 【取在用集合】此刻 status = 4 行已不在导出在用集合内。
            Set<String> reportKeys = referencePort.referencedReportKeys();
            Set<String> exportKeys = referencePort.referencedExportKeys();

            // 【扫根 A / 根 B】只列一层，逐个 move 到 .trash。
            int sweptA = sweepRoot(reportResolver, REPORT_PREFIX, reportKeys, date);
            int sweptB = sweepRoot(exportResolver, EXPORT_PREFIX, exportKeys, date);
            int total = sweptA + sweptB;

            if (total == 0) {
                log.info(
                        "{} ORPHAN-SWEEP 无孤儿文件，本轮未回收（标记到期导出任务 {} 条，cutoff={}）",
                        LOG_PREFIX,
                        marked,
                        cutoff);
                return 0;
            }
            log.info(
                    "{} ORPHAN-SWEEP 回收完成：报告根 {} 个、导出根 {} 个，共 {} 个；标记到期导出任务 {} 条，cutoff={}",
                    LOG_PREFIX,
                    sweptA,
                    sweptB,
                    total,
                    marked,
                    cutoff);
            return total;
        } catch (RuntimeException ex) {
            log.error("{} ORPHAN-SWEEP 回收失败，本轮放弃并等待下个周期重试（cutoff={}）", LOG_PREFIX, cutoff, ex);
            return 0;
        }
    }

    /** DB 先行：限批标记到期导出任务（{@code status → 4}）。 */
    private int markExpiredTasks(LocalDateTime cutoff) {
        int total = 0;
        for (int round = 1; round <= maxRounds; round++) {
            int marked = reaper.markExpired(cutoff, batchSize);
            total += marked;
            if (marked < batchSize) {
                break;
            }
        }
        return total;
    }

    /** 扫单个根（只列一层）；返回实际回收文件数。 */
    private int sweepRoot(
            ObjectKeyResolver resolver, String prefix, Set<String> referenced, LocalDate date) {
        int moved = 0;
        for (int round = 1; round <= maxRounds; round++) {
            List<Path> orphans = listOrphans(resolver, prefix, referenced);
            if (orphans.isEmpty()) {
                break;
            }
            int capacity = Math.min(batchSize, orphans.size());
            for (int i = 0; i < capacity; i++) {
                if (moveToTrash(resolver, prefix, orphans.get(i), date)) {
                    moved++;
                }
            }
            // 本根已不足一批 ⇒ 不会再有更多孤儿，结束；否则继续下一轮（已移走的文件不再出现）。
            if (orphans.size() < batchSize) {
                break;
            }
        }
        return moved;
    }

    /** 统计单个根内的孤儿数（干跑用）。 */
    private long countOrphans(ObjectKeyResolver resolver, String prefix, Set<String> referenced) {
        return listOrphans(resolver, prefix, referenced).size();
    }

    /** 只列一层：枚举 {@code baseDir/<前缀>/} 的直接子项，取其中未被引用的普通文件（符号链接 / 目录天然跳过）。 */
    private List<Path> listOrphans(
            ObjectKeyResolver resolver, String prefix, Set<String> referenced) {
        Path dir;
        try {
            dir = resolver.resolve(prefix);
        } catch (RuntimeException ex) {
            log.warn("{} ORPHAN-SWEEP 存储根解析失败，跳过该根：{}（{}）", LOG_PREFIX, prefix, ex.getMessage());
            return List.of();
        }
        if (!Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        List<Path> orphans = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (!Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                    continue;
                }
                Path fileName = p.getFileName();
                if (fileName == null) {
                    continue;
                }
                String key = prefix + "/" + fileName;
                if (!referenced.contains(key)) {
                    orphans.add(p);
                }
            }
        } catch (IOException ex) {
            log.warn("{} ORPHAN-SWEEP 列举存储根失败，跳过该根：{}（{}）", LOG_PREFIX, prefix, ex.getMessage());
            return List.of();
        }
        return orphans;
    }

    /** 单文件回收：move 到 {@code <baseDir>/.trash/<yyyyMMdd>/<前缀>/<文件名>}；失败只 warn 并返回 false（不抛）。 */
    private boolean moveToTrash(
            ObjectKeyResolver resolver, String prefix, Path source, LocalDate date) {
        try {
            Path fileName = source.getFileName();
            if (fileName == null) {
                return false;
            }
            Path trashDir =
                    resolver.baseDir()
                            .resolve(trashSubdir)
                            .resolve(TRASH_DATE.format(date))
                            .resolve(prefix);
            Files.createDirectories(trashDir);
            Files.move(
                    source,
                    trashDir.resolve(fileName.toString()),
                    StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException | RuntimeException ex) {
            log.warn("{} ORPHAN-SWEEP 单文件回收失败，跳过并继续：{}（{}）", LOG_PREFIX, source, ex.getMessage());
            return false;
        }
    }
}
