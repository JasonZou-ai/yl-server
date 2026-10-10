package cn.yl.modules.archive.mapper;

import cn.yl.modules.archive.dto.DimensionScoreRow;
import cn.yl.modules.archive.dto.ReportSourceRow;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 家属报告读取的<b>模块自持只读投影 Mapper</b>（D-4）。
 *
 * <p><b>不引用任何兄弟模块的实体/类</b>（archive 与 evaluation/report 是平级兄弟，只依赖 {@code yl-api}）—— 本接口仅发出只读
 * SQL。列名逐列取自 {@code docker/mysql/init/02_schema.sql}。
 *
 * <p><b>数据域（第三闸）</b>：{@code eval_report} 登记于 {@code ORG_COLUMN}（scope=2/4）与 {@code
 * RELATION_ELDER_COLUMN}（scope=5）→ 拦截器自动追加行级条件；{@code report_dimension_score} 登记于 {@code
 * RELATION_VIA_PARENT}（经 {@code eval_report} 反查）。故本 SQL <b>不</b>手写机构/关系过滤。
 *
 * <p><b>最小暴露</b>：不取任何老人明文个人信息列。
 */
@Mapper
public interface FamilyReportSourceMapper {

    /**
     * 分页取该老人<b>已发布</b>报告（{@code status = 1}），{@code published_at} 倒序。
     *
     * @param page 分页对象（MyBatis-Plus 分页拦截器填充 records / total）
     * @param elderId 老人 id（来自 binding.elder_id）
     * @return 分页投影行
     */
    @Select(
            "SELECT r.id AS report_id, r.report_no AS report_no, r.elder_id AS elder_id,"
                    + " r.org_id AS org_id, r.grade_code AS grade_code,"
                    + " r.total_score AS total_score, r.published_at AS published_at"
                    + " FROM eval_report r"
                    + " WHERE r.deleted = 0 AND r.status = 1 AND r.elder_id = #{elderId}"
                    + " ORDER BY r.published_at DESC")
    Page<ReportSourceRow> selectPublishedPage(
            Page<ReportSourceRow> page, @Param("elderId") Long elderId);

    /**
     * 取报告详情头（含初判/终判等级名称，供详情展示）。
     *
     * @param reportId 报告 id
     * @return 投影行；不存在返回 {@code null}
     */
    @Select(
            "SELECT r.id AS report_id, r.report_no, r.order_id, r.elder_id, r.org_id, r.grade_code,"
                    + " r.total_score, r.rule_version_id, r.preliminary_grade_code,"
                    + " r.preliminary_level_name, r.final_level_code, r.final_level_name,"
                    + " r.level_change_reason, r.published_at, r.status"
                    + " FROM eval_report r WHERE r.id = #{reportId} AND r.deleted = 0")
    ReportSourceRow selectReportById(@Param("reportId") Long reportId);

    /**
     * 取报告维度得分（{@code report_dimension_score} 无 {@code deleted} 列）。
     *
     * @param reportId 报告 id
     * @return 维度得分行（按 dimension_code 升序）
     */
    @Select(
            "SELECT s.dimension_code, s.dimension_name, s.raw_score, s.max_score, s.refuse_count"
                    + " FROM report_dimension_score s WHERE s.report_id = #{reportId}"
                    + " ORDER BY s.dimension_code")
    List<DimensionScoreRow> selectDimensionScores(@Param("reportId") Long reportId);

    /**
     * 取该老人<b>已发布</b>报告的<b>趋势点</b>（{@code status = 1}），{@code published_at} <b>升序</b>。
     *
     * <p><b>为何单列一法</b>：{@link #selectPublishedPage} 为列表视图（倒序、且不取等级名称）；趋势图需要等级名称与升序，故另起只读投影。 列名逐列取自
     * {@code 02_schema.sql} 的 {@code eval_report}（与 {@link #selectReportById} 同源）。
     *
     * @param elderId 老人 id（来自 binding.elder_id）
     * @return 趋势投影行（升序；含初判/终判等级名称）
     */
    @Select(
            "SELECT r.id AS report_id, r.grade_code, r.total_score, r.published_at,"
                    + " r.preliminary_level_name, r.final_level_name"
                    + " FROM eval_report r"
                    + " WHERE r.deleted = 0 AND r.status = 1 AND r.elder_id = #{elderId}"
                    + " ORDER BY r.published_at ASC")
    List<ReportSourceRow> selectTrendPoints(@Param("elderId") Long elderId);
}
