package cn.yl.modules.account.service;

import cn.yl.api.security.LoginUser;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.account.domain.entity.Org;
import cn.yl.modules.account.dto.OrgContactView;
import cn.yl.modules.account.mapper.OrgMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

/**
 * 所属机构公开联系方式查询服务（CR-M2-009 B-1-a）：{@code GET /api/v1/orgs/current/contact}。
 *
 * <p><b>背景</b>：{@code rqZ0a3}-B5 与 {@code rR1a6L} 残留⑥ 是<b>同一缺口</b> —— 端侧没有机构联系方式来源，止血后只能留空占位
 * 「以机构实际公告为准」（止血前是虚假热线 {@code 400-000-0000}）。
 *
 * <p><b>★ 为什么端点不接受 {@code orgId}</b>：{@code org} 表<b>只登记 {@code ORG_COLUMN}</b>（见 {@code
 * DataScopePermissionHandler}），在 scope=1/5（老人 / 家属）下落不到任何归属条件，第三闸走「未登记 → 放行」暗区 —— <b>一旦接受 orgId
 * 参数，就是任意机构可读的跨机构读面</b>。故本端点<b>零参数</b>，机构由登录态自证。
 *
 * <p><b>四道闸口径</b>：
 *
 * <ul>
 *   <li><b>第二闸</b>：{@code org:contact:read}（2023，五角色全授予；可见范围<b>不靠权限点区分</b>）；
 *   <li><b>第三闸</b>：本服务<b>不手写归属条件</b>（与 {@link OrgQueryService} 同口径），但<b>额外做一次归属自证</b> —— 取登录态
 *       {@code orgId} 后校验其 ∈ {@code orgIds}，防止登录态装配异常时退化成任意机构读；
 *   <li><b>第四闸</b>：非敏感点，不要求二次验证；
 *   <li><b>错误语义</b>：无所属机构 / 机构不存在 —— <b>同码</b> {@link ErrorCode#NOT_FOUND}（10004，不泄露存在性）。
 * </ul>
 *
 * <p><b>最小字段集</b>：只下发机构名 / 联系人 / 地址，<b>不含</b> {@code contactPhoneEnc} 密文列。
 */
@Service
@RequiredArgsConstructor
public class OrgContactService {

    private final OrgMapper orgMapper;

    /**
     * 查询<b>当前登录态所属机构</b>的公开联系方式。
     *
     * @return 公开联系方式视图（最小字段集）
     * @throws BizException 登录态缺失（{@link ErrorCode#UNAUTHORIZED}）或无所属机构 / 机构不存在（{@link
     *     ErrorCode#NOT_FOUND}，同码不泄露存在性）
     */
    public OrgContactView currentContact() {
        LoginUser user = currentUser();
        Long orgId = user.orgId();
        if (orgId == null && !user.orgIds().isEmpty()) {
            orgId = user.orgIds().get(0);
        }
        if (orgId == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "当前账号无所属机构");
        }
        if (!user.orgIds().isEmpty() && !user.orgIds().contains(orgId)) {
            // 归属自证：登录态 orgId 必须落在 orgIds 内，否则拒（防装配异常退化成跨机构读）
            throw new BizException(ErrorCode.NOT_FOUND, "机构不存在或无权访问: orgId=" + orgId);
        }
        Org org = orgMapper.selectById(orgId);
        if (org == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "机构不存在或无权访问: orgId=" + orgId);
        }
        OrgContactView view = new OrgContactView();
        view.setId(org.getId());
        view.setOrgName(org.getOrgName());
        view.setContactName(org.getContactName());
        view.setAddress(org.getAddress());
        return view;
    }

    private LoginUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof LoginUser user) {
            return user;
        }
        throw new BizException(ErrorCode.UNAUTHORIZED, "未登录或登录已过期");
    }
}
