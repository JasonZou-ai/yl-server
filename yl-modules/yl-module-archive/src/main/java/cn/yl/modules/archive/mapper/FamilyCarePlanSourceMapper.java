package cn.yl.modules.archive.mapper;

import cn.yl.modules.archive.dto.CarePlanRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 家属照护方案读取的<b>模块自持只读投影 Mapper</b>（D-4）。
 *
 * <p><b>不引用任何兄弟模块的实体/类</b>；列名逐列取自 {@code docker/mysql/init/02_schema.sql}。
 *
 * <p><b>数据域（第三闸）</b>：{@code care_plan} 登记于 {@code RELATION_ELDER_COLUMN}（scope=5，直锚 {@code
 * elder_id}）与 {@code SELF_COLUMN}（{@code suggest_by}）；{@code org_id} 由拦截器经 {@code elder} 反查。故本 SQL
 * <b>不</b>手写机构/关系过滤。
 */
@Mapper
public interface FamilyCarePlanSourceMapper {

    /**
     * 取该老人最近一次<b>已确认</b>的照护方案（{@code status = 1}），{@code confirmed_at} 倒序取 1。
     *
     * @param elderId 老人 id（来自 binding.elder_id）
     * @return 方案投影行；无已确认方案返回 {@code null}
     */
    @Select(
            "SELECT p.id, p.report_id, p.elder_id, p.grade_code, p.items_json,"
                    + " p.confirmed_by, p.confirmed_at, p.status"
                    + " FROM care_plan p"
                    + " WHERE p.elder_id = #{elderId} AND p.deleted = 0 AND p.status = 1"
                    + " ORDER BY p.confirmed_at DESC LIMIT 1")
    CarePlanRow selectConfirmedPlan(@Param("elderId") Long elderId);
}
