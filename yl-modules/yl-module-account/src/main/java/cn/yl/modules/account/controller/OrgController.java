package cn.yl.modules.account.controller;

import cn.yl.api.security.Logical;
import cn.yl.api.security.PermissionCode;
import cn.yl.api.security.RequiresPermission;
import cn.yl.common.api.PageResult;
import cn.yl.common.api.R;
import cn.yl.modules.account.dto.OrgQuery;
import cn.yl.modules.account.dto.OrgView;
import cn.yl.modules.account.service.OrgQueryService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * R6 组织管理（{@code GET /api/v1/account/orgs}、{@code /{orgId}}）。
 *
 * <p>权限码 {@code account:org:manage}（2009，ORG_ADMIN）<b>或</b> {@code
 * account:org:manage:read}（2010，ORG_ADMIN） —— OR 语义。可见范围由第三闸 {@code org.id IN (orgIds)}
 * 收口（机构管理员=本机构；监管=只读全局）。
 *
 * <p>详情越界/不存在统一 {@code 10004}（同码，不泄露存在性）。
 */
@RestController
@RequestMapping("/api/v1/account/orgs")
@RequiredArgsConstructor
public class OrgController {

    private final OrgQueryService orgQueryService;

    /** 机构列表（分页）。 */
    @GetMapping
    @RequiresPermission(
            value = {PermissionCode.ACCOUNT_ORG_MANAGE, PermissionCode.ACCOUNT_ORG_MANAGE_READ},
            logical = Logical.OR)
    public R<PageResult<OrgView>> list(OrgQuery query) {
        return R.ok(orgQueryService.list(query));
    }

    /** 机构详情。 */
    @GetMapping("/{orgId}")
    @RequiresPermission(
            value = {PermissionCode.ACCOUNT_ORG_MANAGE, PermissionCode.ACCOUNT_ORG_MANAGE_READ},
            logical = Logical.OR)
    public R<OrgView> detail(@PathVariable Long orgId) {
        return R.ok(orgQueryService.detail(orgId));
    }
}
