package cn.yl.modules.supervise.mapper;

import cn.yl.modules.supervise.dto.SuperviseAggregateRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 跨域只读聚合投影 Mapper（B10）。
 *
 * <p><b>不引用兄弟模块的类</b>（supervise 与 evaluation 是平级兄弟，只依赖 {@code yl-api}）—— 仅发出只读 SQL。列名取自 {@code
 * 02_schema.sql}。
 *
 * <p>取值口径：{@code eval_order.status} 为 {@code VARCHAR(16)}（值 {@code DRAFT/SUBMITTED/PUBLISHED/
 * REJECTED/VOID/ARCHIVED}）；{@code eval_order.grade_code} 带 {@code GRADE_} 前缀。
 *
 * <p><b>数据域</b>：{@code eval_order} 已登记 {@code ORG_COLUMN}/{@code SELF_COLUMN}，拦截器会追加行级条件；本 SQL 显式
 * {@code org_id = #{orgId}} 以锁定上报机构。<b>刻意不使用表别名</b>，避免拦截器追加的 {@code 表名.org_id} 在别名化后失效。
 */
@Mapper
public interface SuperviseAggregateMapper {

    /**
     * 按月/机构聚合评估域计数。
     *
     * @param orgId 机构 id
     * @param month 月份（{@code YYYY-MM}）
     * @return 聚合行
     */
    @Select(
            "SELECT COUNT(*) AS assessment_count,"
                    + " SUM(CASE WHEN eval_order.status = 'PUBLISHED' THEN 1 ELSE 0 END) AS published_count,"
                    + " SUM(CASE WHEN eval_order.grade_code = 'GRADE_INTACT' THEN 1 ELSE 0 END)"
                    + "   AS grade_intact_count,"
                    + " SUM(CASE WHEN eval_order.grade_code IN ('GRADE_SEVERE','GRADE_TOTAL')"
                    + "   THEN 1 ELSE 0 END) AS grade_severe_count"
                    + " FROM eval_order"
                    + " WHERE eval_order.deleted = 0"
                    + " AND eval_order.org_id = #{orgId}"
                    + " AND DATE_FORMAT(eval_order.created_at, '%Y-%m') = #{month}")
    SuperviseAggregateRow aggregate(@Param("orgId") Long orgId, @Param("month") String month);
}
