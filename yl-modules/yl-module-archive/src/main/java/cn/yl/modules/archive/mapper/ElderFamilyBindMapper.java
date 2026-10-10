package cn.yl.modules.archive.mapper;

import cn.yl.modules.archive.domain.entity.ElderFamilyBind;
import cn.yl.modules.archive.domain.retention.ExpiredFamilyBindingExpirer;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 亲情绑定 Mapper（elder_family_bind，D-4）。
 *
 * <p>所有查询经 MyBatis-Plus 生成 SQL，因而自动受第三闸 {@code DataScopePermissionHandler} 的行级收口：
 *
 * <ul>
 *   <li>scope=5（家属）：本表按 {@code family_user_id = 我}（{@code relationSegment} 特判）；
 *   <li>scope=2（机构管理员）：经父表 {@code elder.org_id IN (myOrgs)} 反查锚收口（D-4 ★F3）；
 *   <li>scope=1（老人本人）：经父表 {@code elder.user_id = 我} 反查锚收口（D-4 ★F1）。
 * </ul>
 *
 * <p>软删列 {@code deleted} 为普通列（非生成列），第三闸不改写它，故查询须<b>显式</b> {@code deleted = 0}。
 *
 * <p>到期流转动作（{@link ExpiredFamilyBindingExpirer}，D-5 裁决 D5-D3）走<b>手写 SQL</b>：批量状态流转，MyBatis-Plus 的
 * {@code updateById} 无法表达。<b>★ 只改 {@code status}、绝不碰 {@code deleted}</b>（唯一键 {@code active_uk}
 * 让位语义： 只有 {@code deleted = 1} 才释放唯一键，改它会破坏「重新申请复用活性行」）。{@code updated_at} 由 DDL 的 {@code ON UPDATE
 * CURRENT_TIMESTAMP} 自动维护，无需显式写。
 *
 * <p><b>不加 {@code @InterceptorIgnore}</b>：本动作在定时任务内执行、<b>无登录态</b>，第三闸 {@code
 * DataScopePermissionHandler#getSqlSegment} 首行 {@code user == null → return null} 即 no-op，与既有两个
 * retention Mapper 同。
 */
@Mapper
public interface ElderFamilyBindMapper
        extends BaseMapper<ElderFamilyBind>, ExpiredFamilyBindingExpirer {

    /**
     * 流转已到期但仍生效的亲情绑定：{@code status 2 → 4}（只读语义：行保留、{@code deleted} 不变）。
     *
     * <p>幂等由 {@code status = 2} 条件保证（流转后即离开命中集合）；{@code auth_end_at IS NOT NULL} 是硬条件（长期有效行永不流转）。
     * 命中列有 {@code idx_expire(auth_end_at, status)} 索引支撑，{@code LIMIT} 控制单批规模。
     */
    @Override
    @Update(
            "UPDATE elder_family_bind SET status = 4"
                    + " WHERE status = 2 AND deleted = 0 AND auth_end_at IS NOT NULL AND auth_end_at < #{cutoff}"
                    + " ORDER BY auth_end_at LIMIT #{limit}")
    int expireExpired(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    /** 统计已到期但仍生效、尚未流转的亲情绑定数。 */
    @Override
    @Select(
            "SELECT COUNT(*) FROM elder_family_bind"
                    + " WHERE status = 2 AND deleted = 0 AND auth_end_at IS NOT NULL AND auth_end_at < #{cutoff}")
    long countExpired(@Param("cutoff") LocalDateTime cutoff);
}
