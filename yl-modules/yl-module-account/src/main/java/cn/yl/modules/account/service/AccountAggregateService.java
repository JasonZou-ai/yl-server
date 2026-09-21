package cn.yl.modules.account.service;

import cn.yl.modules.account.domain.RoleCode;
import cn.yl.modules.account.domain.entity.SysOrgMember;
import cn.yl.modules.account.domain.entity.SysUser;
import cn.yl.modules.account.dto.AccountProfile;
import cn.yl.modules.account.mapper.SysOrgMemberMapper;
import cn.yl.modules.account.mapper.SysPermissionMapper;
import cn.yl.modules.account.mapper.SysRoleMapper;
import cn.yl.modules.account.mapper.SysUserMapper;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 账号聚合服务（rky1SW 五角色账号体系的核心聚合）。
 *
 * <p>按用户聚合：账号实体、角色码、权限码（去重）、机构成员关系，并推导出默认机构与跨角色「最宽松」数据域， 供 rwkav6 鉴权四道闸构造 {@code
 * LoginUser}。权限矩阵逐格可回溯：perms 完全来自 sys_role_permission 种子。
 */
@Service
@RequiredArgsConstructor
public class AccountAggregateService {

    private final SysUserMapper userMapper;
    private final SysRoleMapper roleMapper;
    private final SysPermissionMapper permMapper;
    private final SysOrgMemberMapper orgMemberMapper;

    /**
     * 加载用户聚合档案；用户不存在返回 {@code null}。
     *
     * @param userId 用户 ID
     */
    public AccountProfile loadProfile(Long userId) {
        SysUser user = userMapper.selectById(userId);
        if (user == null) {
            return null;
        }
        List<String> roles = roleMapper.selectRoleCodesByUserId(userId);
        List<String> perms = permMapper.selectPermCodesByUserId(userId);
        List<SysOrgMember> members = orgMemberMapper.selectByUserId(userId);
        List<Long> orgIds = members.stream().map(SysOrgMember::getOrgId).distinct().toList();
        int dataScope =
                roles.stream()
                        .map(RoleCode::of)
                        .mapToInt(RoleCode::dataScope)
                        .max()
                        .orElse(RoleCode.ELDER.dataScope());
        Long defaultOrg =
                members.stream()
                        .filter(m -> Integer.valueOf(1).equals(m.getIsDefault()))
                        .map(SysOrgMember::getOrgId)
                        .findFirst()
                        .orElse(null);
        AccountProfile profile = new AccountProfile();
        profile.setUserId(userId);
        profile.setOrgId(defaultOrg);
        profile.setRoles(roles);
        profile.setPerms(perms);
        profile.setOrgIds(orgIds);
        profile.setDataScope(dataScope);
        return profile;
    }
}
