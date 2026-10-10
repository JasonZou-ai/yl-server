package cn.yl.modules.report.domain.retention;

import java.util.Set;

/**
 * 孤儿文件回收 · 引用源端口（P2-7，保留期链路第 5 个动作）。
 *
 * <p>语义是「返回当前<b>仍被 DB 行引用</b>的全部对象键」。清扫器据此判定：位于存储根内、且键不在引用集合中的<b>普通文件</b>即为孤儿，可回收。
 *
 * <h3>★ 两个根的「在用」口径（用户裁决 D2-A / D1-B）</h3>
 *
 * <ul>
 *   <li><b>报告根</b>（{@code report/}）：{@link #referencedReportKeys()} = {@code
 *       eval_report(deleted=0)} 行的 {@code report_no} <b>推导键</b> {@code "report/" + report_no +
 *       ".pdf"} <b>∪</b> 非空 {@code pdf_cos_key}。
 *       <p>为什么必须是<b>并集</b>：下载端点 {@code GET /reports/{reportNo}/pdf} 按 {@code report_no}
 *       <b>推导</b>键名、<b>根本不读</b> {@code pdf_cos_key}。只反查 {@code pdf_cos_key} 会误删「文件已落盘但 {@code
 *       pdf_cos_key} 为 NULL/滞后」的在用 PDF（如 {@code write()} 成功、{@code updateById} 失败）。
 *   <li><b>导出根</b>（{@code export/}）：{@link #referencedExportKeys()} = {@code export_task(status≠4)}
 *       行的 {@code task_no} 推导键 {@code "export/" + task_no + ".csv"} <b>∪</b> 非空 {@code
 *       file_cos_key}。
 *       <p>{@code export_task} <b>无 {@code deleted} 列</b>（行永不消失），故其"在用"集合以 {@code status}
 *       口径收口：到期行先由清扫器置 {@code status = 4}（{@link ExpiredExportTaskReaper}），随即退出本集合。
 * </ul>
 *
 * <p><b>★ 手写 SQL 不走全局逻辑删除</b>：{@code eval_report} 的引用投影必须<b>显式</b>写 {@code deleted = 0}
 * （MyBatis-Plus 的全局逻辑删除只改写 Mapper 生成 SQL，手写 {@code @Select} 不受其约束）。此守卫由 {@code RetentionChainDbIT}
 * 的 T8 用例钉死。
 */
public interface RetentionReferencePort {

    /**
     * 报告根（{@code report/}）当前仍在用的对象键集合（{@code report_no} 推导键 ∪ 非空 {@code pdf_cos_key}）。
     *
     * @return 在用键集合（不含 {@code deleted = 1} 的报告行）
     */
    Set<String> referencedReportKeys();

    /**
     * 导出根（{@code export/}）当前仍在用的对象键集合（{@code task_no} 推导键 ∪ 非空 {@code file_cos_key}）。
     *
     * @return 在用键集合（不含 {@code status = 4} 的已过期任务行）
     */
    Set<String> referencedExportKeys();
}
