package cn.yl.modules.supervise.mapper;

import cn.yl.modules.supervise.domain.entity.SuperviseReport;
import cn.yl.modules.supervise.domain.retention.ExpiredSuperviseReportArchiver;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 监管上报 Mapper（supervise_report）。
 *
 * <p>归档动作（{@link ExpiredSuperviseReportArchiver}，D-5 裁决 D5-D1）走<b>手写 SQL</b>：归档是「批量只读封存」而非按主键的单行
 * 更新，MyBatis-Plus 的 {@code updateById} 无法表达，且批量场景下无并发编辑竞争。手写 SQL 同时明确绕开全局逻辑删除改写（本表无 {@code deleted}
 * 列），语义显式可读。
 *
 * <p><b>★ 本表无 {@code updated_at} 列</b>，归档 UPDATE 只写 {@code archived_at}；{@code retain_until}
 * <b>不清空</b> （留存事实，封存后仍需可查）；{@code status}（上报状态）<b>不改动</b>——归档态由 {@code archived_at} 单独承载。
 *
 * <p><b>不加 {@code @InterceptorIgnore}</b>：本动作在定时任务内执行、<b>无登录态</b>，第三闸 {@code
 * DataScopePermissionHandler#getSqlSegment} 首行 {@code user == null → return null} 即 no-op，与既有两个
 * retention Mapper 同。
 */
@Mapper
public interface SuperviseReportMapper
        extends BaseMapper<SuperviseReport>, ExpiredSuperviseReportArchiver {

    /**
     * 归档已过保留期的监管上报记录：写入 {@code archived_at}，**行保留、{@code status} 不变**。
     *
     * <p>幂等由 {@code archived_at IS NULL} 条件保证（归档后即离开命中集合）；{@code retain_until IS NOT NULL}
     * 是硬条件（未写保留期的行永不归档）。命中列有 {@code idx_retain(retain_until)} 索引支撑，{@code LIMIT} 控制单批规模。
     */
    @Override
    @Update(
            "UPDATE supervise_report SET archived_at = NOW()"
                    + " WHERE archived_at IS NULL AND retain_until IS NOT NULL AND retain_until < #{cutoff}"
                    + " ORDER BY retain_until LIMIT #{limit}")
    int archiveExpired(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /** 统计已过保留期但尚未归档的监管上报记录数。 */
    @Override
    @Select(
            "SELECT COUNT(*) FROM supervise_report"
                    + " WHERE archived_at IS NULL AND retain_until IS NOT NULL AND retain_until < #{cutoff}")
    long countExpired(@Param("cutoff") LocalDateTime cutoff);
}
