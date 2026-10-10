package cn.yl.api.storage;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 对象键解析器（存储根解析 + 键→绝对路径 + 防目录穿越的<b>唯一真源</b>）。
 *
 * <p><b>为什么放 {@code yl-api}</b>：报告 PDF 落盘（{@code yl-module-evaluation}）与导出 CSV 落盘（{@code
 * yl-module-report}）及孤儿文件清扫器（{@code yl-module-report}）三处都要把「对象键」解析为本地路径。三处各写一份解析逻辑，一旦写入侧
 * 配置口径变化，清扫器会<b>静默扫空目录</b>（看起来"没有孤儿"）——与 CR-M2-006「不经 Mapper 的直读绕过治理」同类的"副本漂移"缺陷。故收敛到此处， 与先例 {@link
 * cn.yl.api.report.PdfFontLocator} 同址（模块依赖方向为 {@code modules → api}，反向引用会成环）。
 *
 * <p><b>语义</b>：
 *
 * <ul>
 *   <li>存储根 = {@code configuredDir}（非空即用其 {@code trim()} 值）；为空则回退 {@code
 *       ${java.io.tmpdir}/<fallbackSubDir>} ——与既有写入口逐字一致；
 *   <li>{@link #resolve(String)} 对越界键（目录穿越）抛 {@link BizException}（{@link ErrorCode#PARAM_INVALID}），
 *       与改造前两处私有 {@code resolve()} 的异常类型与文案完全相同（保持零行为变更）；
 *   <li>本类无状态、可自由构造，不作为 Spring Bean 暴露。
 * </ul>
 */
public final class ObjectKeyResolver {

    private final String configuredDir;

    private final String fallbackSubDir;

    /**
     * @param configuredDir 配置目录（可为 {@code null}/空白 → 回退 {@code ${java.io.tmpdir}/<fallbackSubDir>}）
     * @param fallbackSubDir 回退子目录名（如 {@code yl-reports} / {@code yl-exports}）
     */
    public ObjectKeyResolver(String configuredDir, String fallbackSubDir) {
        this.configuredDir = configuredDir == null ? "" : configuredDir.trim();
        this.fallbackSubDir = fallbackSubDir;
    }

    /** 存储根（绝对路径、已规范化）。 */
    public Path baseDir() {
        if (!configuredDir.isBlank()) {
            return Paths.get(configuredDir).toAbsolutePath().normalize();
        }
        return Paths.get(System.getProperty("java.io.tmpdir"), fallbackSubDir)
                .toAbsolutePath()
                .normalize();
    }

    /**
     * 对象键 → 绝对路径；越界（目录穿越）抛 {@link BizException}（{@link ErrorCode#PARAM_INVALID}）。
     *
     * @param key 对象键，形如 {@code report/<reportNo>.pdf}、{@code export/<taskNo>.csv}
     * @return 解析后的绝对路径（已规范化）
     */
    public Path resolve(String key) {
        Path base = baseDir();
        Path file = base.resolve(key).normalize();
        if (!file.startsWith(base)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "非法存储路径: " + key);
        }
        return file;
    }
}
