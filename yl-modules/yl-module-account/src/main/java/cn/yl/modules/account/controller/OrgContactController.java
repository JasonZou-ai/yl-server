package cn.yl.modules.account.controller;

import cn.yl.api.security.PermissionCode;
import cn.yl.api.security.RequiresPermission;
import cn.yl.common.api.R;
import cn.yl.modules.account.dto.OrgContactView;
import cn.yl.modules.account.service.OrgContactService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 所属机构<b>公开</b>联系方式接口（CR-M2-009 B-1-a）：{@code GET /api/v1/orgs/current/contact}。
 *
 * <p>背景：{@code rqZ0a3}-B5 与 {@code rR1a6L} 残留⑥ 是同一缺口 —— 端侧无机构联系方式来源，止血后留空占位 「以机构实际公告为准」（止血前为虚假热线
 * {@code 400-000-0000}）。本端点补上这一格。
 *
 * <p><b>为什么单独一个控制器而不并入 {@link OrgController}</b>：{@code OrgController} 是<b>机构管理读面</b> （{@code
 * /api/v1/account/orgs}，权限码 {@code account:org:manage*}，仅机构管理员 / 监管可持）；本控制器是
 * <b>五角色通用的最小公开读面</b>，权限码 {@code org:contact:read}。二者权限码语义不同，拆开后各自的鉴权段说明、 契约 {@code x-data-scope}
 * 与回归断言互不干扰（先例：{@code ReportController} / {@code ReportListController} 同为 {@code
 * /api/v1/evaluation/reports} 前缀下的独立控制器）。
 *
 * <p><b>零参数</b>：不接受 {@code orgId} —— {@code org} 表只登记 {@code ORG_COLUMN}，scope=1/5 下第三闸走 「未登记 →
 * 放行」暗区，接受参数即成跨机构读面。归属由登录态自证（见 {@link OrgContactService}）。
 *
 * <p><b>无构造器级 SpotBugs 豁免</b>：本控制器只注入一个只读服务 Bean 并在方法体内调用，按本项目口径 （「不接受多余的豁免」，同 {@code
 * US_USELESS_SUPPRESSION}）<b>不加</b> {@code @SuppressFBWarnings}。
 */
@RestController
@RequestMapping("/api/v1/orgs")
@RequiredArgsConstructor
public class OrgContactController {

    private final OrgContactService orgContactService;

    /**
     * 当前登录态所属机构的公开联系方式（最小字段集）。
     *
     * <p>错误语义沿用全站口径：业务失败 HTTP 200 + {@code R.code}；无所属机构 / 不存在<b>同码</b>（10004，不泄露存在性）。
     */
    @GetMapping("/current/contact")
    @RequiresPermission(PermissionCode.ORG_CONTACT_READ)
    public R<OrgContactView> currentContact() {
        return R.ok(orgContactService.currentContact());
    }
}
