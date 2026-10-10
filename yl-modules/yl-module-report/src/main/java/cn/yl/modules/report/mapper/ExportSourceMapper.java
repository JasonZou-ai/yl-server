package cn.yl.modules.report.mapper;

import cn.yl.modules.report.dto.ExportSourceRow;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 跨域只读投影 Mapper（B9）。
 *
 * <p><b>不引用任何兄弟模块的实体/类</b>（report 与 evaluation/archive 是平级兄弟，只依赖 {@code yl-api}）—— 本接口仅发出只读
 * SQL。列名逐列取自 {@code docker/mysql/init/02_schema.sql}。
 *
 * <p><b>数据域（第三闸）</b>：{@code eval_order}/{@code eval_report}/{@code elder} 均登记于 {@code ORG_COLUMN} →
 * 拦截器会为每张表追加机构条件（scope=2 时 {@code org_id IN (orgIds)}；scope=4 监管不限）。故本 SQL <b>不</b>手写机构过滤，
 * 且<b>刻意不使用表别名</b>——避免拦截器追加的 {@code 表名.org_id} 在别名化后失效（列名错误会直接 SQL 报错）。
 *
 * <p><b>最小暴露</b>：只取脱敏列（{@code id_card_mask}）与密文列（{@code name_enc}/{@code phone_enc}），不取任何明文个人信息列。
 */
@Mapper
public interface ExportSourceMapper {

    /**
     * 取评估单导出投影（按机构数据域收口，最近优先）。
     *
     * @param limit 取数上限（服务层取 {@code maxSyncRows + 1} 以探测超限）
     * @return 投影行集合
     */
    @Select(
            "SELECT eval_order.order_no        AS order_no,"
                    + " COALESCE(eval_report.grade_code, eval_order.grade_code) AS grade_code,"
                    + " elder.name_enc           AS elder_name_enc,"
                    + " elder.id_card_mask       AS id_card_mask,"
                    + " elder.phone_enc          AS phone_enc,"
                    + " eval_order.status        AS status,"
                    + " eval_order.org_id        AS org_id,"
                    + " eval_order.published_at  AS published_at"
                    + " FROM eval_order"
                    + " LEFT JOIN eval_report ON eval_report.order_id = eval_order.id"
                    + " AND eval_report.deleted = 0"
                    + " LEFT JOIN elder ON elder.id = eval_order.elder_id"
                    + " WHERE eval_order.deleted = 0"
                    + " ORDER BY eval_order.created_at DESC"
                    + " LIMIT #{limit}")
    List<ExportSourceRow> selectEvalList(@Param("limit") int limit);
}
