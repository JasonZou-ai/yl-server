package cn.yl.modules.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import cn.yl.modules.account.domain.entity.SysUser;
import cn.yl.modules.account.dto.AccountProfile;
import cn.yl.modules.account.mapper.SysOrgMemberMapper;
import cn.yl.modules.account.mapper.SysPermissionMapper;
import cn.yl.modules.account.mapper.SysRoleMapper;
import cn.yl.modules.account.mapper.SysUserMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 数据域聚合不变式单测（D4-R7 及其同批修复的<b>前置</b>）。
 *
 * <p><b>本测试守的是一条被多处改锚依赖的前提</b>：
 *
 * <pre>
 *   dataScope = 1（本人）  ⟺  账号是「纯 ELDER」（或无任何角色）
 *   只要账号还持任一员工角色（ASSESSOR / ORG_ADMIN / SUPERVISOR / FAMILY），
 *   聚合取「多角色最宽松值」就会把它抬到 2 / 4 / 5，永不落 1。
 * </pre>
 *
 * <p>为何必须先钉死这条： {@code SELF_COLUMN} / {@code SELF_VIA_PARENT} / {@code SELF_DENY_TABLES} 三个集合只在
 * scope=1 分支生效。 D4-R7（{@code eval_report}）、R8（{@code care_plan}）、F2（{@code
 * eval_order}）的修法都是「按老人归属收口」， 其安全性完全建立在「scope=1 里不会有员工」之上。而该行为此前<b>只是实现细节、无契约保护</b>—— 一旦有人把 {@code
 * max()} 改成 {@code min()} 或引入单角色员工态，员工就会被收口成"我作为老人"， 静默恒空（fail-closed 但功能消失），且 CI 六项作业全绿不报红。
 *
 * <p>因此本测试是改锚的前置门禁：它红，就说明改锚的前提没了，不得继续。
 */
class AccountAggregateServiceTest {

    private static final Long USER_ID = 1001L;

    private final SysUserMapper userMapper = mock(SysUserMapper.class);
    private final SysRoleMapper roleMapper = mock(SysRoleMapper.class);
    private final SysPermissionMapper permMapper = mock(SysPermissionMapper.class);
    private final SysOrgMemberMapper orgMemberMapper = mock(SysOrgMemberMapper.class);

    private final AccountAggregateService service =
            new AccountAggregateService(userMapper, roleMapper, permMapper, orgMemberMapper);

    private AccountProfile loadWithRoles(String... roles) {
        when(userMapper.selectById(USER_ID)).thenReturn(new SysUser());
        when(roleMapper.selectRoleCodesByUserId(USER_ID)).thenReturn(List.of(roles));
        when(permMapper.selectPermCodesByUserId(USER_ID)).thenReturn(List.of());
        when(orgMemberMapper.selectByUserId(USER_ID)).thenReturn(List.of());
        return service.loadProfile(USER_ID);
    }

    @Test
    @DisplayName("纯 ELDER 单角色 ⇒ dataScope=1（本人）；这是唯一合法的 scope=1 形态")
    void pureElderFallsToSelfScope() {
        assertThat(loadWithRoles("ELDER").getDataScope()).isEqualTo(1);
    }

    @Test
    @DisplayName("★ 核心不变式：ELDER 叠加任一员工角色后，dataScope 绝不为 1")
    void elderPlusAnyStaffRoleNeverFallsToSelfScope() {
        assertThat(loadWithRoles("ELDER", "ASSESSOR").getDataScope()).isNotEqualTo(1);
        assertThat(loadWithRoles("ELDER", "ORG_ADMIN").getDataScope()).isNotEqualTo(1);
        assertThat(loadWithRoles("ELDER", "SUPERVISOR").getDataScope()).isNotEqualTo(1);
        assertThat(loadWithRoles("ELDER", "FAMILY").getDataScope()).isNotEqualTo(1);
    }

    @Test
    @DisplayName("叠加后的取值符合「多角色取最宽松」：ELDER+ASSESSOR=2、+ORG_ADMIN=2、+SUPERVISOR=4、+FAMILY=5")
    void combinedScopeUsesWidestRole() {
        assertThat(loadWithRoles("ELDER", "ASSESSOR").getDataScope()).isEqualTo(2);
        assertThat(loadWithRoles("ELDER", "ORG_ADMIN").getDataScope()).isEqualTo(2);
        assertThat(loadWithRoles("ELDER", "SUPERVISOR").getDataScope()).isEqualTo(4);
        // 5-关系级数值最大但范围最窄，取 max 会被它拿下 —— 此处只记录行为，提醒"数值序≠宽严序"
        assertThat(loadWithRoles("ELDER", "FAMILY").getDataScope()).isEqualTo(5);
    }

    @Test
    @DisplayName("员工单角色永不落 scope=1（评估员=2 / 机构管理员=2 / 监管=4 / 家属=5）")
    void staffOnlyRolesNeverFallToSelfScope() {
        assertThat(loadWithRoles("ASSESSOR").getDataScope()).isEqualTo(2);
        assertThat(loadWithRoles("ORG_ADMIN").getDataScope()).isEqualTo(2);
        assertThat(loadWithRoles("SUPERVISOR").getDataScope()).isEqualTo(4);
        assertThat(loadWithRoles("FAMILY").getDataScope()).isEqualTo(5);
    }

    @Test
    @DisplayName("无任何角色 ⇒ 回落 ELDER 默认 scope=1（失败即拒方向，安全）")
    void noRoleFallsBackToElderSelfScope() {
        assertThat(loadWithRoles().getDataScope()).isEqualTo(1);
    }

    @Test
    @DisplayName("用户不存在 ⇒ 返回 null，不产生任何默认档案")
    void missingUserReturnsNull() {
        when(userMapper.selectById(USER_ID)).thenReturn(null);
        assertThat(service.loadProfile(USER_ID)).isNull();
    }
}
