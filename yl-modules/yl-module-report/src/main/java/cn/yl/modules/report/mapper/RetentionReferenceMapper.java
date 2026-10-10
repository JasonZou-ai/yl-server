package cn.yl.modules.report.mapper;

import cn.yl.modules.report.domain.retention.ExpiredExportTaskReaper;
import cn.yl.modules.report.domain.retention.RetentionReferencePort;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 保留期清理链路 · P2-7 孤儿文件回收的<b>手写 SQL 门面</b>（跨域只读投影 + 导出任务到期标记）。
 *
 * <p>本 Mapper 同时实现两个端口（{@link RetentionReferencePort} / {@link ExpiredExportTaskReaper}），对应 {@code
 * eval_report}（evaluation 域）与 {@code export_task}（本模块）两张表。列名逐列取自 {@code
 * docker/mysql/init/02_schema.sql}。
 *
 * <p><b>★ 手写 SQL 不走全局逻辑删除</b>：MyBatis-Plus 的全局逻辑删除（{@code logic-delete-field: deleted}）只改写 <b>由
 * BaseMapper / 条件构造器生成</b>的 SQL；手写 {@code @Select} <b>不受其约束</b>。故 {@code eval_report} 的引用投影<b>必须
 * 显式</b>写 {@code deleted = 0}，否则会把已软删报告的行也算作"在用"，让孤儿文件永远无法回收（反之为更危险的误删——若漏写而查询的是"在用"以外的口径）。
 *
 * <p>{@code export_task} <b>无 {@code deleted} 列</b>（不可软删，行永不消失），故其"在用"以 {@code status} 口径收口：引用集合排除
 * {@code status = 4}（已过期）行。
 *
 * <p>两个「在用集合」均由 {@code default} 方法把 {@code @Select} 结果收敛为 {@link Set}（同键去重、查询侧 O(1) 命中）， 与 {@link
 * RetentionReferencePort} 的契约一致。
 */
@Mapper
public interface RetentionReferenceMapper extends RetentionReferencePort, ExpiredExportTaskReaper {

    /** 报告根在用键投影：{@code deleted = 0} 报告的 {@code report_no} 推导键 ∪ 非空 {@code pdf_cos_key}（D2-A 并集）。 */
    @Select(
            "SELECT CONCAT('report/', report_no, '.pdf') AS k FROM eval_report WHERE deleted = 0"
                    + " UNION"
                    + " SELECT pdf_cos_key FROM eval_report WHERE deleted = 0 AND pdf_cos_key IS NOT NULL"
                    + " AND pdf_cos_key <> ''")
    List<String> selectReportKeys();

    /** 导出根在用键投影：{@code status <> 4} 任务的 {@code task_no} 推导键 ∪ 非空 {@code file_cos_key}。 */
    @Select(
            "SELECT CONCAT('export/', task_no, '.csv') AS k FROM export_task WHERE status <> 4"
                    + " UNION"
                    + " SELECT file_cos_key FROM export_task WHERE status <> 4 AND file_cos_key IS NOT NULL"
                    + " AND file_cos_key <> ''")
    List<String> selectExportKeys();

    @Override
    default Set<String> referencedReportKeys() {
        return new HashSet<>(selectReportKeys());
    }

    @Override
    default Set<String> referencedExportKeys() {
        return new HashSet<>(selectExportKeys());
    }

    /** 到期标记（D1-B）：{@code expire_at} 非空、已过期、且尚未标记的行 → {@code status = 4}（行保留，仅改状态）。 */
    @Override
    @Update(
            "UPDATE export_task SET status = 4"
                    + " WHERE expire_at IS NOT NULL AND expire_at < #{cutoff} AND status <> 4"
                    + " ORDER BY expire_at LIMIT #{limit}")
    int markExpired(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /** 统计已过期但仍未标记的导出任务数（干跑与标记后核对）。 */
    @Override
    @Select(
            "SELECT COUNT(*) FROM export_task"
                    + " WHERE expire_at IS NOT NULL AND expire_at < #{cutoff} AND status <> 4")
    long countExpired(@Param("cutoff") LocalDateTime cutoff);
}
