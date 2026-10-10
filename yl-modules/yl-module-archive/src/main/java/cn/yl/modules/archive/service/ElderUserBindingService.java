package cn.yl.modules.archive.service;

import cn.yl.api.security.LoginUser;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.archive.domain.entity.Elder;
import cn.yl.modules.archive.dto.UserBindingView;
import cn.yl.modules.archive.mapper.ElderMapper;
import cn.yl.modules.archive.mapper.ElderUserOrgMemberMapper;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code elder↔user} 绑定服务（D-4，写 {@code elder.user_id}）。
 *
 * <p>机构管理员把某 ELDER 账号绑定为某 {@code elder} 的<b>本人账号</b>（写 {@code elder.user_id}）。三道显式校验：
 *
 * <ol>
 *   <li>目标 {@code sys_user} 须属本机构（{@code sys_org_member} 有效行 ∩ 登录态 {@code orgIds}），否则 {@link
 *       ErrorCode#ROLE_NOT_PERMITTED}（20003）；
 *   <li>{@code elder} 存在且未删除（第三闸按机构收口 → 越界/不存在 {@link ErrorCode#NOT_FOUND}（10004，同码））；
 *   <li>{@code elder.user_id} 未被他人占用（非空且 ≠ 请求 {@code userId} → {@link ErrorCode#CONFLICT}（10005））。
 * </ol>
 *
 * <p><b>★ 承重语义：老人↔账号为 1:1</b>（{@code elder.uk_elder_user} 唯一）。{@code uk_elder_user} 冲突由 {@link
 * DuplicateKeyException} 兜底为 10005（<b>绝不 500</b>）。
 *
 * <p><b>★ 零 DDL 约束（D-4a §1.8 裁决）</b>：设计稿早期 §1.2/§2.2 曾提到「写 {@code user_id} + {@code auth_end_at}」，但
 * {@code elder} 表<b>无 {@code auth_end_at} 列</b>（该列只存在于 {@code elder_family_bind}），且本卡零 DDL → <b>只写
 * {@code elder.user_id}</b>。请求体<b>不再接收 {@code authEndAt}</b>（不接收、不落库、不告警）：接受但不落库等于静默丢弃一个<b>安全语义</b>
 * 字段（调用方会误以为有效期已生效，实际为永久），违「宁可失败，不可假数据」。「有效期」的合法承载是 {@code
 * elder_authorization}（ER-07），本卡明令不接（D4-R3）。
 */
@Service
@RequiredArgsConstructor
public class ElderUserBindingService {

    private static final int DELETED_NO = 0;

    private final ElderMapper elderMapper;

    private final ElderUserOrgMemberMapper orgMemberMapper;

    /**
     * 绑定 {@code elder.user_id}（老人↔账号 1:1）。
     *
     * @param elderId 老人 id（path）
     * @param userId 目标 ELDER 账号 id
     * @param operator 登录态操作者（机构管理员；身份取登录态，请求体不承载）
     * @return 绑定视图
     */
    @Transactional(rollbackFor = Exception.class)
    public UserBindingView bind(Long elderId, Long userId, LoginUser operator) {
        if (userId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "userId 必填");
        }
        // ① 目标账号须属本机构（越权 → 20003）
        List<Long> memberOrgIds = orgMemberMapper.selectActiveOrgIds(userId);
        boolean inMyOrgs =
                memberOrgIds != null && memberOrgIds.stream().anyMatch(operator.orgIds()::contains);
        if (!inMyOrgs) {
            throw new BizException(ErrorCode.ROLE_NOT_PERMITTED, "目标账号不属于本机构：userId=" + userId);
        }
        // ② elder 存在且未删除（第三闸 → 越界/不存在同码 10004）
        Elder elder = elderMapper.selectById(elderId);
        if (elder == null || !Integer.valueOf(DELETED_NO).equals(elder.getDeleted())) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        // ③ 未被其他账号占用（否则 10005）
        if (elder.getUserId() != null && !elder.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.CONFLICT, "该老人已绑定其他账号");
        }
        // ④ 写 elder.user_id（uk_elder_user 冲突 → 10005，绝不 500）
        Elder update = new Elder();
        update.setId(elder.getId());
        update.setUserId(userId);
        try {
            elderMapper.updateById(update);
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.CONFLICT, "该账号已绑定其他老人");
        }
        return toView(elder.getId(), userId, LocalDateTime.now());
    }

    private UserBindingView toView(Long elderId, Long userId, LocalDateTime boundAt) {
        UserBindingView view = new UserBindingView();
        view.setElderId(elderId);
        view.setUserId(userId);
        view.setBoundAt(boundAt);
        return view;
    }
}
