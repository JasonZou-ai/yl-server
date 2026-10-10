package cn.yl.modules.archive.controller;

import cn.yl.api.security.Audit;
import cn.yl.api.security.LoginUser;
import cn.yl.api.security.PermissionCode;
import cn.yl.api.security.RequiresPermission;
import cn.yl.api.security.SecurityUtils;
import cn.yl.common.api.PageResult;
import cn.yl.common.api.R;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.common.idempotent.Idempotent;
import cn.yl.modules.archive.dto.ElderCreateRequest;
import cn.yl.modules.archive.dto.ElderQuery;
import cn.yl.modules.archive.dto.ElderView;
import cn.yl.modules.archive.dto.UserBindingRequest;
import cn.yl.modules.archive.dto.UserBindingView;
import cn.yl.modules.archive.service.ElderArchiveService;
import cn.yl.modules.archive.service.ElderUserBindingService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 老人档案接口（D-2）：建档 / 列表 / 详情。
 *
 * <p><b>鉴权口径（CR-M2-002 铁律）</b>：
 *
 * <ul>
 *   <li><b>第二闸（授权）</b>：每个端点标注 {@link RequiresPermission}，权限码与 {@code sys_permission} 种子逐格对齐（{@code
 *       elder:archive:create} / {@code elder:archive:read}）。
 *   <li><b>操作者来源</b>：一律 {@link SecurityUtils#requireUser()} 取登录态；<b>请求体不承载身份</b> （{@code createdBy}
 *       等由服务端取登录态）。
 *   <li><b>第三闸（数据域）</b>：列表/详情的可见范围由 {@code DataScopePermissionHandler} 按登录态 {@code dataScope}
 *       行级收口（老人本人={@code elder.user_id=me}，评估员/机构管理员=本机构， 家属=关系级，监管=只读全局），服务层不再手写机构过滤。
 * </ul>
 *
 * <p>建档的<b>归属机构</b>：{@code orgId} 可省略 —— 缺省取登录态默认机构；显式传值须落在本人机构域 {@code LoginUser.orgIds()}
 * 内，否则拒绝（{@link ErrorCode#ROLE_NOT_PERMITTED} = 20003）——机构域外的行 在第三闸下对创建者自己也永久不可见，属静默数据污染。
 */
@RestController
@RequestMapping("/api/v1/elders")
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification =
                                "ElderArchiveService / ElderUserBindingService 均为 Spring 容器管理的无状态单例 Bean，"
                                        + "构造器注入是标准 DI 用法"))
public class ElderController {

    private final ElderArchiveService service;

    private final ElderUserBindingService userBindingService;

    /**
     * 新建老人档案（{@code Idempotency-Key} 必填）。
     *
     * <p>明文提交，服务端加密落库；返回脱敏视图（姓名 {@code 张*}、身份证掩码串）。
     */
    @PostMapping
    @RequiresPermission(PermissionCode.ELDER_ARCHIVE_CREATE)
    @Idempotent(verifyBody = true)
    public R<ElderView> create(@Valid @RequestBody ElderCreateRequest request) {
        LoginUser user = SecurityUtils.requireUser();
        if (request.getOrgId() == null) {
            request.setOrgId(user.requireOrgId());
        } else if (!user.orgIds().contains(request.getOrgId())) {
            throw new BizException(
                    ErrorCode.ROLE_NOT_PERMITTED, "归属机构超出本人数据域：orgId=" + request.getOrgId());
        }
        return R.ok(service.create(request, user));
    }

    /** 老人列表（分页 + 关键词/状态/机构筛选；可见范围由第三闸收口）。 */
    @GetMapping
    @RequiresPermission(PermissionCode.ELDER_ARCHIVE_READ)
    public R<PageResult<ElderView>> list(ElderQuery query) {
        return R.ok(service.list(query));
    }

    /**
     * 老人档案详情（脱敏）。
     *
     * <p>{@code @PathVariable} <b>显式命名</b>（本项目曾因 {@code -parameters} 缺失导致未命名 {@code @PathVariable}
     * 报 500）。
     */
    @GetMapping("/{elderId}")
    @RequiresPermission(PermissionCode.ELDER_ARCHIVE_READ)
    public R<ElderView> detail(@PathVariable("elderId") Long elderId) {
        return R.ok(service.detail(elderId));
    }

    /**
     * 绑定老人本人登录账号（D-4，{@code elder↔user}，机构侧）。
     *
     * <p>权限码 {@code account:org:manage}（ORG_ADMIN 独占）；机构数据域 {@code elder.org_id IN (myOrgs)}
     * 由第三闸自动收口；请求体只带 {@code userId}（<b>不含</b> {@code authEndAt}，D-4a §1.8 收窄契约），操作者取登录态。幂等键必填；留痕
     * {@code ELDER_USER_BIND}。
     *
     * <p><b>★ 老人↔账号 1:1</b>（{@code uk_elder_user} 唯一）：冲突返回 {@code 10005}，<b>绝不 500</b>。
     */
    @PostMapping("/{elderId}/user-binding")
    @RequiresPermission(PermissionCode.ACCOUNT_ORG_MANAGE)
    @Idempotent(verifyBody = true)
    @Audit(action = "ELDER_USER_BIND", bizType = "ELDER")
    public R<UserBindingView> bindUser(
            @PathVariable("elderId") Long elderId, @Valid @RequestBody UserBindingRequest request) {
        return R.ok(
                userBindingService.bind(elderId, request.getUserId(), SecurityUtils.requireUser()));
    }
}
