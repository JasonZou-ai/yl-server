package cn.yl.modules.archive.controller;

import cn.yl.api.security.Audit;
import cn.yl.api.security.Logical;
import cn.yl.api.security.PermissionCode;
import cn.yl.api.security.RequiresPermission;
import cn.yl.api.security.SecurityUtils;
import cn.yl.common.api.PageResult;
import cn.yl.common.api.R;
import cn.yl.common.idempotent.Idempotent;
import cn.yl.modules.archive.dto.FamilyBindingCreateRequest;
import cn.yl.modules.archive.dto.FamilyBindingQuery;
import cn.yl.modules.archive.dto.FamilyBindingRejectRequest;
import cn.yl.modules.archive.dto.FamilyBindingView;
import cn.yl.modules.archive.dto.FamilyCarePlanView;
import cn.yl.modules.archive.dto.FamilyReportDetailView;
import cn.yl.modules.archive.dto.FamilyReportView;
import cn.yl.modules.archive.dto.FamilyTrendPointView;
import cn.yl.modules.archive.service.FamilyBindingService;
import cn.yl.modules.archive.service.FamilyReportQueryService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 家属端亲情绑定接口（D-4，{@code /api/v1/family/bindings/**}）。
 *
 * <p><b>鉴权口径（CR-M2-002 铁律）</b>：
 *
 * <ul>
 *   <li><b>第二闸（授权）</b>：每个端点标注 {@link RequiresPermission}；{@code approve} 为 {@code
 *       account:org:manage} （★C1：ORG_ADMIN 独占，修复 FAMILY 自我放行越权）；{@code unbind} 用 <b>OR</b> 语义（含敏感码
 *       ⇒ 强制第四闸）。
 *   <li><b>操作者来源</b>：一律 {@link SecurityUtils#requireUser()} 取登录态；<b>请求体不承载身份</b>（{@code
 *       familyUserId} 等由服务端取登录态）。
 *   <li><b>第三闸（数据域）</b>：可见范围由 {@code DataScopePermissionHandler} 按登录态 {@code dataScope} 行级收口。
 * </ul>
 *
 * <p><b>留痕</b>：4 个写端点各挂 {@link Audit}（{@code FAMILY_BIND_APPLY/APPROVE/REJECT/UNBIND}，解绑 {@code
 * sensitive=1}）。
 *
 * <p><b>读端点</b>（{@code report:view}）：报告列表 / 详情 / 趋势 / 照护经 {@code relationId} 收口（{@link
 * cn.yl.modules.archive.service.FamilyReportQueryService#requireMyActiveBinding}），越权 / 未生效 / 过期同码
 * {@code 10004}。
 *
 * <p><b>构造器注入</b>：仅注入无状态单例 Bean；构造器注入是标准 DI 用法。D-4a 收尾（R-2 给 {@code FamilyBindingService} 引入 {@code
 * UpdateWrapper} 复位分支）后，SpotBugs 对该 service 触发 {@code EI_EXPOSE_REP2}（构造器注入单例的经典误报）→ 按 {@code
 * ElderController} 先例补<b>构造器级</b> {@code @SuppressFBWarnings}。（注：无抑制写法在本模块此前可用，但不可作为 长期假设 ——
 * 误报随分析输入变化，故改为与 {@code ElderController} 同构的显式抑制。）
 */
@RestController
@RequestMapping("/api/v1/family/bindings")
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification =
                                "FamilyBindingService / FamilyReportQueryService 均为 Spring 容器管理的无状态单例 Bean，"
                                        + "构造器注入是标准 DI 用法"))
public class FamilyBindingController {

    private final FamilyBindingService bindingService;

    private final FamilyReportQueryService reportQueryService;

    /** 我的绑定列表（分页；可见范围由第三闸收口）。 */
    @GetMapping
    @RequiresPermission(PermissionCode.ACCOUNT_FAMILY_BIND)
    public R<PageResult<FamilyBindingView>> list(FamilyBindingQuery query) {
        return R.ok(bindingService.list(query));
    }

    /** 发起亲情绑定（核验 + 幂等 + 留痕）。 */
    @PostMapping
    @RequiresPermission(PermissionCode.ACCOUNT_FAMILY_BIND)
    @Idempotent(verifyBody = true)
    @Audit(action = "FAMILY_BIND_APPLY", bizType = "ELDER_FAMILY_BIND")
    public R<FamilyBindingView> create(@Valid @RequestBody FamilyBindingCreateRequest request) {
        return R.ok(bindingService.apply(request, SecurityUtils.requireUser()));
    }

    /** 机构审核通过绑定（★C1：权限码 {@code account:org:manage} 为 ORG_ADMIN 独占，FAMILY 无此码 → 第二闸即挡 20003）。 */
    @PostMapping("/{id}/approve")
    @RequiresPermission(PermissionCode.ACCOUNT_ORG_MANAGE)
    @Idempotent(verifyBody = true)
    @Audit(action = "FAMILY_BIND_APPROVE", bizType = "ELDER_FAMILY_BIND")
    public R<FamilyBindingView> approve(@PathVariable("id") Long id) {
        return R.ok(bindingService.approve(id, SecurityUtils.requireUser()));
    }

    /** 老人侧拒绝绑定（仅 ELDER；数据域 self 经 {@code elder.user_id} 反查锚收口）。 */
    @PostMapping("/{id}/reject")
    @RequiresPermission(PermissionCode.ACCOUNT_FAMILY_BIND_REJECT)
    @Idempotent(verifyBody = true)
    @Audit(action = "FAMILY_BIND_REJECT", bizType = "ELDER_FAMILY_BIND")
    public R<FamilyBindingView> reject(
            @PathVariable("id") Long id,
            @RequestBody(required = false) FamilyBindingRejectRequest req) {
        String reason = req == null ? null : req.getReason();
        return R.ok(bindingService.reject(id, reason, SecurityUtils.requireUser()));
    }

    /**
     * 解绑亲情关系（敏感：{@code OR} 含 {@code account:family:unbind} ⇒ {@code PermissionAspect} 强制第四闸；缺凭据 →
     * {@code 10008}）。
     */
    @PostMapping("/{id}/unbind")
    @RequiresPermission(
            value = {PermissionCode.ACCOUNT_FAMILY_BIND, PermissionCode.ACCOUNT_FAMILY_UNBIND},
            logical = Logical.OR)
    @Idempotent(verifyBody = true)
    @Audit(action = "FAMILY_BIND_UNBIND", bizType = "ELDER_FAMILY_BIND", sensitive = true)
    public R<Void> unbind(@PathVariable("id") Long id) {
        bindingService.unbind(id, SecurityUtils.requireUser());
        return R.ok();
    }

    /** 我的绑定关系下已发布报告列表（分页）。 */
    @GetMapping("/{relationId}/reports")
    @RequiresPermission(PermissionCode.REPORT_VIEW)
    public R<PageResult<FamilyReportView>> reports(
            @PathVariable("relationId") Long relationId, FamilyBindingQuery query) {
        return R.ok(reportQueryService.reports(relationId, query));
    }

    /** 报告详情（含维度得分 + 照护条目；{@code reportId} 越界同码 {@code 10004}）。 */
    @GetMapping("/{relationId}/reports/{reportId}")
    @RequiresPermission(PermissionCode.REPORT_VIEW)
    public R<FamilyReportDetailView> reportDetail(
            @PathVariable("relationId") Long relationId, @PathVariable("reportId") Long reportId) {
        return R.ok(reportQueryService.detail(relationId, reportId));
    }

    /** 能力变化趋势（{@code published_at} 升序）。 */
    @GetMapping("/{relationId}/trend")
    @RequiresPermission(PermissionCode.REPORT_VIEW)
    public R<List<FamilyTrendPointView>> trend(@PathVariable("relationId") Long relationId) {
        return R.ok(reportQueryService.trend(relationId));
    }

    /** 最新已确认照护方案（无已确认方案 → 空方案 {@code code=0}）。 */
    @GetMapping("/{relationId}/care-plan")
    @RequiresPermission(PermissionCode.REPORT_VIEW)
    public R<FamilyCarePlanView> carePlan(@PathVariable("relationId") Long relationId) {
        return R.ok(reportQueryService.carePlan(relationId));
    }
}
