package cn.yl.modules.report.service.retention;

import static org.assertj.core.api.Assertions.assertThat;

import cn.yl.modules.report.domain.retention.ExpiredExportTaskReaper;
import cn.yl.modules.report.domain.retention.RetentionReferencePort;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 孤儿文件回收任务测试（P2-7）。
 *
 * <p>真实 tmp 目录 + 假端口：断言「孤儿被移入隔离目录、在用文件不动」、D2 关键负例（{@code pdf_cos_key} 为 NULL 但 {@code report_no} 在用
 * ⇒ 不动）、限批、幂等、dry-run 零移动、单文件失败隔离、符号链接跳过、{@code .trash} 不被二次扫。
 */
class OrphanFileSweepTaskTest {

    private static final DateTimeFormatter TRASH_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    @TempDir Path tempDir;

    private Path reportRoot;

    private Path exportRoot;

    private FakeReferencePort port;

    private FakeReaper reaper;

    @BeforeEach
    void setUp() {
        reportRoot = tempDir.resolve("reports");
        exportRoot = tempDir.resolve("exports");
        port = new FakeReferencePort();
        reaper = new FakeReaper();
    }

    @Test
    @DisplayName("孤儿被移入 .trash，有引用的文件不动；两根合计为返回值")
    void recyclesOrphansAndKeepsReferenced() throws IOException {
        write(reportRoot.resolve("report/keep.pdf"));
        write(reportRoot.resolve("report/orphan-a.pdf"));
        write(exportRoot.resolve("export/keep.csv"));
        write(exportRoot.resolve("export/orphan-b.csv"));
        port.reportKeys.add("report/keep.pdf");
        port.exportKeys.add("export/keep.csv");

        int swept = task(500, 20, false, true).sweepOrphanFiles();

        assertThat(swept).as("报告根 1 个 + 导出根 1 个").isEqualTo(2);
        assertThat(Files.exists(reportRoot.resolve("report/orphan-a.pdf"))).isFalse();
        assertThat(Files.exists(exportRoot.resolve("export/orphan-b.csv"))).isFalse();
        assertThat(Files.exists(reportRoot.resolve("report/keep.pdf"))).as("在用报告不动").isTrue();
        assertThat(Files.exists(exportRoot.resolve("export/keep.csv"))).as("在用导出不动").isTrue();
        assertThat(trashOf(reportRoot, "report", "orphan-a.pdf")).as(".trash 下可寻回").isRegularFile();
        assertThat(trashOf(exportRoot, "export", "orphan-b.csv")).as(".trash 下可寻回").isRegularFile();
    }

    @Test
    @DisplayName("★D2 负例：pdf_cos_key 为 NULL 但 report_no 在用 ⇒ 文件不动")
    void keepsReportWhoseDerivedKeyIsReferencedEvenWhenPdfKeyNull() throws IOException {
        write(reportRoot.resolve("report/RP-1.pdf"));
        write(reportRoot.resolve("report/orphan.pdf"));
        // 在用集合仅含 report_no 推导键，不含任何 pdf_cos_key（模拟 pdf_cos_key 为 NULL / 滞后）
        port.reportKeys.add("report/RP-1.pdf");

        int swept = task(500, 20, false, true).sweepOrphanFiles();

        assertThat(swept).as("仅孤儿被回收").isEqualTo(1);
        assertThat(Files.exists(reportRoot.resolve("report/RP-1.pdf")))
                .as("只反查 pdf_cos_key 会误删它——故 D2-A 用并集")
                .isTrue();
        assertThat(Files.exists(reportRoot.resolve("report/orphan.pdf"))).isFalse();
    }

    @Test
    @DisplayName("限批：batch-size × max-rounds 是单次回收上限")
    void respectsBatchSizeAndMaxRounds() throws IOException {
        for (int i = 1; i <= 5; i++) {
            write(reportRoot.resolve("report/orphan-" + i + ".pdf"));
        }

        int swept = task(2, 2, false, true).sweepOrphanFiles();

        assertThat(swept).as("batch=2 × rounds=2 ⇒ 最多回收 4").isEqualTo(4);
        assertThat(countFiles(reportRoot.resolve("report"))).as("剩 1 个孤儿待下轮").isEqualTo(1);
    }

    @Test
    @DisplayName("幂等：命中即移出根，第二次为 0")
    void idempotentOnSecondRun() throws IOException {
        write(reportRoot.resolve("report/orphan.pdf"));
        OrphanFileSweepTask task = task(500, 20, false, true);

        assertThat(task.sweepOrphanFiles()).isEqualTo(1);
        assertThat(task.sweepOrphanFiles()).as("第二次无孤儿").isZero();
    }

    @Test
    @DisplayName("dry-run：零移动，只统计（且不标记到期导出任务）")
    void dryRunMovesNothing() throws IOException {
        write(reportRoot.resolve("report/orphan.pdf"));
        reaper.expiredCount = 3L;

        int swept = task(500, 20, true, true).sweepOrphanFiles();

        assertThat(swept).isZero();
        assertThat(Files.exists(reportRoot.resolve("report/orphan.pdf"))).as("零移动").isTrue();
        assertThat(trashDir(reportRoot, "report")).as("dry-run 不建隔离目录").doesNotExist();
        assertThat(reaper.markCalls).as("dry-run 不标记到期导出任务").isZero();
    }

    @Test
    @DisplayName("单文件失败隔离：一个失败不阻断其余，失败文件留在根内")
    void singleFileFailureIsIsolated() throws IOException {
        write(reportRoot.resolve("report/bad.pdf"));
        write(reportRoot.resolve("report/good.pdf"));
        // 让 bad.pdf 的隔离目标是一个「非空目录」⇒ Files.move 必然失败
        Path blocked = trashDir(reportRoot, "report").resolve("bad.pdf");
        Files.createDirectories(blocked);
        Files.writeString(blocked.resolve("occupied"), "x");

        int swept = task(500, 20, false, true).sweepOrphanFiles();

        assertThat(swept).as("仅 good.pdf 成功").isEqualTo(1);
        assertThat(Files.exists(reportRoot.resolve("report/good.pdf"))).isFalse();
        assertThat(Files.exists(reportRoot.resolve("report/bad.pdf")))
                .as("失败文件不被静默丢弃，留在根内待下轮")
                .isTrue();
    }

    @Test
    @DisplayName("只列一层：子目录不是普通文件 ⇒ 跳过且不递归（子目录内文件不受影响）")
    void skipsSubdirectoriesWithoutRecursing() throws IOException {
        write(reportRoot.resolve("report/subdir/nested.pdf"));
        write(reportRoot.resolve("report/orphan.pdf"));

        int swept = task(500, 20, false, true).sweepOrphanFiles();

        assertThat(swept).as("仅根内一层的孤儿被回收").isEqualTo(1);
        assertThat(Files.exists(reportRoot.resolve("report/orphan.pdf"))).isFalse();
        assertThat(Files.exists(reportRoot.resolve("report/subdir/nested.pdf")))
                .as("子目录天然跳过，绝不 Files.walk 递归")
                .isTrue();
    }

    @Test
    @DisplayName("符号链接跳过（NOFOLLOW_LINKS），链接目标不受影响（仅平台支持真符号链接时执行，否则跳过）")
    void skipsSymbolicLinks() throws IOException {
        Path outside = tempDir.resolve("outside.txt");
        Files.writeString(outside, "payload");
        Path link = reportRoot.resolve("report/link.pdf");
        Files.createDirectories(link.getParent());
        try {
            Files.createSymbolicLink(link, outside);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.assumeTrue(false, "当前平台不支持创建符号链接（需要权限 / 开发者模式），跳过该用例：" + e.getMessage());
        }
        // ★ 平台可能「假成功」：createSymbolicLink 不抛异常，却静默降级为普通文件（实测 Windows 沙箱文件系统
        //   即如此：mklink 报成功但 dir 显示 0 字节普通文件，readSymbolicLink 抛 NotLinkException）。
        //   故以 isSymbolicLink 为唯一判据决定能否继续——否则本用例在大写权限 shell 下断言普通文件、
        //   在缺权限 shell 下被跳过，同一提交产出 SUCCESS/FAILURE 两种结果（门禁 flaky，比红更糟）。
        if (!Files.isSymbolicLink(link)) {
            Assumptions.assumeTrue(
                    false, "当前平台/文件系统不支持真正的符号链接（createSymbolicLink 静默降级为普通文件），跳过该用例");
        }

        int swept = task(500, 20, false, true).sweepOrphanFiles();

        assertThat(swept).as("符号链接不是普通文件，天然跳过").isZero();
        assertThat(Files.exists(link, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(Files.exists(outside)).as("链接目标不受影响").isTrue();
    }

    @Test
    @DisplayName(".trash 不在前缀内 ⇒ 不被二次扫描（天然幂等）")
    void trashIsNotRescanned() throws IOException {
        Path old = trashDir(reportRoot, "report").resolve("old.pdf");
        Files.createDirectories(old.getParent());
        Files.writeString(old, "already-trashed");
        write(reportRoot.resolve("report/orphan.pdf"));

        int swept = task(500, 20, false, true).sweepOrphanFiles();

        assertThat(swept).as("仅根内孤儿被回收，.trash 被跳过").isEqualTo(1);
        assertThat(old).as("既有隔离文件不动").isRegularFile();
    }

    @Test
    @DisplayName("到期导出任务先置 status=4（DB 先行），且不阻断文件回收")
    void marksExpiredExportTasksBeforeSweep() throws IOException {
        write(exportRoot.resolve("export/orphan.csv"));
        reaper.markedPerCall = 2;

        int swept = task(500, 20, false, true).sweepOrphanFiles();

        assertThat(reaper.markCalls).as("DB 先行：标记被调用").isEqualTo(1);
        assertThat(reaper.lastLimit).isEqualTo(500);
        assertThat(swept).isEqualTo(1);
    }

    @Test
    @DisplayName("export-expire-mark=false：不标记导出任务，仍回收文件")
    void skipsMarkingWhenDisabled() throws IOException {
        write(exportRoot.resolve("export/orphan.csv"));

        int swept = task(500, 20, false, false).sweepOrphanFiles();

        assertThat(reaper.markCalls).isZero();
        assertThat(swept).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private OrphanFileSweepTask task(
            int batchSize, int maxRounds, boolean dryRun, boolean exportExpireMark) {
        return new OrphanFileSweepTask(
                port,
                reaper,
                reportRoot.toString(),
                exportRoot.toString(),
                batchSize,
                maxRounds,
                dryRun,
                ".trash",
                exportExpireMark);
    }

    private static void write(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, "x");
    }

    private static long countFiles(Path dir) throws IOException {
        try (var stream = Files.list(dir)) {
            return stream.filter(Files::isRegularFile).count();
        }
    }

    private static Path trashDir(Path root, String prefix) {
        return root.resolve(".trash").resolve(TRASH_DATE.format(LocalDate.now())).resolve(prefix);
    }

    private static Path trashOf(Path root, String prefix, String fileName) {
        return trashDir(root, prefix).resolve(fileName);
    }

    /** 假引用源端口。 */
    private static final class FakeReferencePort implements RetentionReferencePort {

        private final Set<String> reportKeys = new HashSet<>();

        private final Set<String> exportKeys = new HashSet<>();

        @Override
        public Set<String> referencedReportKeys() {
            return reportKeys;
        }

        @Override
        public Set<String> referencedExportKeys() {
            return exportKeys;
        }
    }

    /** 假到期标记端口。 */
    private static final class FakeReaper implements ExpiredExportTaskReaper {

        private int markCalls;

        private int markedPerCall;

        private long expiredCount;

        private int lastLimit;

        @Override
        public int markExpired(LocalDateTime cutoff, int limit) {
            markCalls++;
            lastLimit = limit;
            return markedPerCall;
        }

        @Override
        public long countExpired(LocalDateTime cutoff) {
            return expiredCount;
        }
    }
}
