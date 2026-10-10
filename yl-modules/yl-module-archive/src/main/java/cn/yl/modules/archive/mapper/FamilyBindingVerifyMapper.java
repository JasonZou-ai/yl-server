package cn.yl.modules.archive.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 亲情绑定核验只读投影 Mapper（D-4 ★F2）—— <b>本卡唯一绕过第三闸的读</b>。
 *
 * <p><b>为何必须绕过第三闸（法律上的不可达）</b>：绑定申请时目标老人<b>尚未与本账号建立生效绑定</b>，而 {@code elder} 在 scope=5（家属关系级）下的可见性经
 * {@code MY_ELDER_SUBQUERY}（{@code status=2 AND deleted=0 AND auth_end_at 未过期}）收口 ——
 * 申请前该子查询<b>恒空</b>，故无法经第三闸读到目标老人来核验。若不绕过，则申请链无法核验 {@code elderName}/{@code idCardNo}（★F2 表象）。
 *
 * <p><b>绕过机制（可执行实现，非"口头声明"）</b>：本 Mapper 以接口级 {@link InterceptorIgnore}
 * {@code @InterceptorIgnore(dataPermission = "1")} 声明<b>本 Mapper 全部语句跳过第三闸</b>（<b>豁免为接口级、全方法生效</b>：
 * 既然生效范围是全方法，则新方法绝不可返回老人字段/PII —— 返回类型被单测反射钉死为标量）。MyBatis-Plus {@code
 * DataPermissionInterceptor#beforeQuery} 会先问 {@code
 * InterceptorIgnoreHelper.willIgnoreDataPermission(mappedStatementId)}，命中即不改写 SQL。<b>这正是设计
 * §10.8「唯一绕过第三闸的读」的落地方式</b>； 若缺失该注解，scope=5 的家属在申请前查 {@code elder} 会被改写成 {@code elder.id IN (SELECT
 * b.elder_id FROM elder_family_bind … status=2 …)} → 恒空 → 申请<b>永远</b>失败。
 *
 * <p><b>三重补偿（把"绕过第三闸"的暴露面压到最小）</b>：
 *
 * <ol>
 *   <li><b>最小化</b>：本 Mapper 的<b>全部方法只返回标量</b>（{@code int} / {@code boolean}）—— <b>绝不</b> SELECT 任何
 *       老人字段（姓名/证件/机构/账号态一律不外泄）；单测 `FamilyBindingVerifyMapperTest` 用反射钉死返回类型 ⊆ {@code {int,
 *       boolean, long}}。
 *   <li><b>防枚举（同码）</b>：{@code countExactMatch} 的入参是<b>服务端计算</b>的 {@code name_hash} + {@code
 *       id_card_hash}（明文姓名/证件不落库、GCM 密文 IV 随机不可比对）；服务层对 {@code count == 0} 与"老人不存在"返回
 *       <b>同一错误码</b>（{@code 10004 NOT_FOUND}），不泄露存在性。
 *   <li><b>频控</b>：服务层按 {@code userId} 限流（60s / 10 次，超限 {@code 10429}），防批量枚举 {@code elderId}。
 * </ol>
 *
 * <p><b>列名口径</b>：逐列照 {@code docker/mysql/init/02_schema.sql} 的 {@code elder} 表（{@code id} / {@code
 * name_hash} / {@code id_card_hash} / {@code user_id} / {@code deleted}），<b>禁表别名臆造</b>。
 */
@Mapper
@InterceptorIgnore(dataPermission = "1")
public interface FamilyBindingVerifyMapper {

    /**
     * 精确匹配计数：{@code elderId}+{@code name_hash}+{@code id_card_hash} 三者同时命中且未删。
     *
     * <p>只返回计数（服务层仅判 {@code > 0}）；{@code 0} 与"不存在"同码（不泄露存在性）。
     *
     * @param elderId 目标老人 id
     * @param nameHash 姓名 HMAC 摘要（服务端计算）
     * @param idCardHash 身份证 HMAC 摘要（服务端计算）
     * @return 命中行数（0 或 1）
     */
    @Select(
            "SELECT COUNT(*) FROM elder"
                    + " WHERE id = #{elderId} AND name_hash = #{nameHash}"
                    + " AND id_card_hash = #{idCardHash} AND deleted = 0")
    int countExactMatch(
            @Param("elderId") Long elderId,
            @Param("nameHash") String nameHash,
            @Param("idCardHash") String idCardHash);

    /**
     * 老人是否已有登录账号（供 {@code create} 落 {@code status=0/1} 分派，§3.3 判定规则）。
     *
     * <p><b>只返回 boolean</b>：仅泄漏 1 bit「该老人是否已绑定账号」，而申请人已用 {@code name_hash}+{@code id_card_hash}
     * 精确自证身份 → 无新增枚举面。与 {@link #countExactMatch} 共用同一频控 + 防枚举纪律。
     *
     * @param elderId 目标老人 id
     * @return {@code true} = 老人已有登录账号（{@code elder.user_id IS NOT NULL}）
     */
    @Select("SELECT EXISTS(SELECT 1 FROM elder WHERE id = #{elderId} AND user_id IS NOT NULL)")
    boolean hasLoginAccount(@Param("elderId") Long elderId);
}
