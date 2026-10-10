package cn.yl.modules.archive.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 目标账号机构归属的<b>模块自持只读投影 Mapper</b>（D-4，{@code elder↔user} 绑定）。
 *
 * <p><b>为何自建（而非复用 account 的 {@code SysOrgMemberMapper}）</b>：archive 与 account 是平级兄弟模块，只依赖 {@code
 * yl-api}，不得引用兄弟模块类；且同名简单类会与 account 的 {@code SysOrgMemberMapper} 在 Spring 扫描时产生<b>同名 Bean 冲突</b>
 * （注解扫描按简单类名生成 Bean 名），故本卡在 archive 侧另建投影（列名逐列取自 {@code 02_schema.sql} 的 {@code sys_org_member}）。
 *
 * <p><b>数据域（第三闸）</b>：{@code sys_org_member} 登记于 {@code ORG_COLUMN}（{@code org_id}）→ scope=2
 * 下拦截器自动追加 {@code org_id IN (myOrgs)}，故本 SQL 只按 {@code user_id} 取归属机构集合，机构收口由拦截器 + 服务层双重保证。
 *
 * <p><b>最小暴露</b>：仅返回 {@code org_id} 标量列，不取任何账号个人信息。
 */
@Mapper
public interface ElderUserOrgMemberMapper {

    /**
     * 取目标账号当前<b>有效</b>归属的机构 id 集合（{@code status=1 AND deleted=0}）。
     *
     * @param userId 目标账号 {@code sys_user.id}
     * @return 归属机构 id 列表（空表示无有效归属）
     */
    @Select(
            "SELECT m.org_id FROM sys_org_member m"
                    + " WHERE m.user_id = #{userId} AND m.status = 1 AND m.deleted = 0")
    List<Long> selectActiveOrgIds(@Param("userId") Long userId);
}
