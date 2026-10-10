package cn.yl.modules.archive.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yl.api.security.LoginUser;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.archive.domain.entity.Elder;
import cn.yl.modules.archive.dto.UserBindingView;
import cn.yl.modules.archive.mapper.ElderMapper;
import cn.yl.modules.archive.mapper.ElderUserOrgMemberMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;

/**
 * {@code elder↔user} 绑定服务测试（D-4，设计 §1.2 / §2.2）。
 *
 * <p>三道显式校验的机械验收：① 目标账号非本机构 → {@code 20003}；② {@code elder} 越界/不存在 → {@code 10004}；③ 被他人占用 → {@code
 * 10005}；④ {@code uk_elder_user} 冲突（{@link DuplicateKeyException}）→ {@code 10005}（<b>绝不 500</b>）。
 *
 * <p><b>★ 1:1 承重语义（D-4a §1.8）</b>：{@code elder↔账号} 为 1:1（{@code elder.uk_elder_user} 唯一）。两条显式断言：
 * {@code bindSecondElderToSameUserReturnsConflict}（同一账号绑第二个老人）与 {@code
 * bindSecondUserToAlreadyBoundElderReturnsConflict}（第二个账号绑已绑定老人），均须 {@code 10005}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ElderUserBindingServiceTest {

    private static final long ELDER_ID = 500L;
    private static final long ELDER_USER_ID = 900000000000000003L;
    private static final long OTHER_USER_ID = 900000000000000099L;

    @Mock private ElderMapper elderMapper;
    @Mock private ElderUserOrgMemberMapper orgMemberMapper;

    private ElderUserBindingService service;

    @BeforeEach
    void setUp() {
        service = new ElderUserBindingService(elderMapper, orgMemberMapper);
    }

    // ---------------- ① 机构校验（20003） ----------------

    @Test
    void bindRejectsUserOutsideMyOrgs() {
        // 目标账号只归属机构 2；操作者仅有机构 1 → 越权
        when(orgMemberMapper.selectActiveOrgIds(ELDER_USER_ID)).thenReturn(List.of(2L));

        BizException ex =
                assertThrowsBiz(() -> service.bind(ELDER_ID, ELDER_USER_ID, adminOperator()));

        assertEquals(ErrorCode.ROLE_NOT_PERMITTED.getCode(), ex.getCode());
        verify(elderMapper, never()).updateById(any(Elder.class));
    }

    @Test
    void bindRejectsUserWithNoOrgMembership() {
        when(orgMemberMapper.selectActiveOrgIds(ELDER_USER_ID)).thenReturn(List.of());

        BizException ex =
                assertThrowsBiz(() -> service.bind(ELDER_ID, ELDER_USER_ID, adminOperator()));

        assertEquals(ErrorCode.ROLE_NOT_PERMITTED.getCode(), ex.getCode());
    }

    // ---------------- ② elder 存在性（10004，同码） ----------------

    @Test
    void bindRejectsMissingElder() {
        when(orgMemberMapper.selectActiveOrgIds(ELDER_USER_ID)).thenReturn(List.of(1L));
        when(elderMapper.selectById(ELDER_ID)).thenReturn(null);

        BizException ex =
                assertThrowsBiz(() -> service.bind(ELDER_ID, ELDER_USER_ID, adminOperator()));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void bindRejectsSoftDeletedElder() {
        when(orgMemberMapper.selectActiveOrgIds(ELDER_USER_ID)).thenReturn(List.of(1L));
        Elder elder = elder(null);
        elder.setDeleted(1);
        when(elderMapper.selectById(ELDER_ID)).thenReturn(elder);

        BizException ex =
                assertThrowsBiz(() -> service.bind(ELDER_ID, ELDER_USER_ID, adminOperator()));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    // ---------------- ③ 占用判（10005） ----------------

    @Test
    void bindRejectsWhenOccupiedByOtherUser() {
        when(orgMemberMapper.selectActiveOrgIds(ELDER_USER_ID)).thenReturn(List.of(1L));
        when(elderMapper.selectById(ELDER_ID)).thenReturn(elder(OTHER_USER_ID));

        BizException ex =
                assertThrowsBiz(() -> service.bind(ELDER_ID, ELDER_USER_ID, adminOperator()));

        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
        verify(elderMapper, never()).updateById(any(Elder.class));
    }

    /** ★ 1:1 断言 ②：第二个账号绑「已被他人绑定的老人」→ {@code 10005}（服务层占用判，绝不更新）。 */
    @Test
    void bindSecondUserToAlreadyBoundElderReturnsConflict() {
        when(orgMemberMapper.selectActiveOrgIds(ELDER_USER_ID)).thenReturn(List.of(1L));
        when(elderMapper.selectById(ELDER_ID)).thenReturn(elder(OTHER_USER_ID));

        BizException ex =
                assertThrowsBiz(() -> service.bind(ELDER_ID, ELDER_USER_ID, adminOperator()));

        assertEquals(
                ErrorCode.CONFLICT.getCode(),
                ex.getCode(),
                "老人已绑定账号 X，第二个账号 Y 绑定同一老人 → 10005（1:1）");
        verify(elderMapper, never()).updateById(any(Elder.class));
    }

    // ---------------- ④ 正常写入 ----------------

    @Test
    void bindWritesUserIdWhenFree() {
        when(orgMemberMapper.selectActiveOrgIds(ELDER_USER_ID)).thenReturn(List.of(1L));
        when(elderMapper.selectById(ELDER_ID)).thenReturn(elder(null));

        UserBindingView view = service.bind(ELDER_ID, ELDER_USER_ID, adminOperator());

        ArgumentCaptor<Elder> captor = ArgumentCaptor.forClass(Elder.class);
        verify(elderMapper).updateById(captor.capture());
        assertEquals(ELDER_USER_ID, captor.getValue().getUserId(), "写入 elder.user_id");
        assertEquals(ELDER_ID, view.getElderId());
        assertEquals(ELDER_USER_ID, view.getUserId());
        assertNotNull(view.getBoundAt());
    }

    @Test
    void bindAllowsRebindingSameUser() {
        when(orgMemberMapper.selectActiveOrgIds(ELDER_USER_ID)).thenReturn(List.of(1L));
        when(elderMapper.selectById(ELDER_ID)).thenReturn(elder(ELDER_USER_ID));

        UserBindingView view = service.bind(ELDER_ID, ELDER_USER_ID, adminOperator());

        assertEquals(ELDER_USER_ID, view.getUserId(), "同账号重复绑定视为幂等成功");
        verify(elderMapper).updateById(any(Elder.class));
    }

    // ---------------- ⑤ 唯一键冲突兜底（10005，不 500） ----------------

    @Test
    void bindMapsDuplicateKeyToConflict() {
        when(orgMemberMapper.selectActiveOrgIds(ELDER_USER_ID)).thenReturn(List.of(1L));
        when(elderMapper.selectById(ELDER_ID)).thenReturn(elder(null));
        doThrow(new DuplicateKeyException("uk_elder_user"))
                .when(elderMapper)
                .updateById(any(Elder.class));

        BizException ex =
                assertThrowsBiz(() -> service.bind(ELDER_ID, ELDER_USER_ID, adminOperator()));

        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
    }

    /**
     * ★ 1:1 断言 ①：同一账号绑「第二个老人」→ 撞 {@code uk_elder_user} → {@code 10005}（绝不 500）。
     *
     * <p>场景：账号 U 已绑定老人 A（未在本次请求的 elder 上体现），现把老人 B（{@code user_id} 为空）绑到 U → 服务层占用判放行，落库时唯一键冲突 →
     * {@link DuplicateKeyException} → 兜底 {@code 10005}。
     */
    @Test
    void bindSecondElderToSameUserReturnsConflict() {
        when(orgMemberMapper.selectActiveOrgIds(ELDER_USER_ID)).thenReturn(List.of(1L));
        when(elderMapper.selectById(ELDER_ID)).thenReturn(elder(null));
        doThrow(new DuplicateKeyException("uk_elder_user"))
                .when(elderMapper)
                .updateById(any(Elder.class));

        BizException ex =
                assertThrowsBiz(() -> service.bind(ELDER_ID, ELDER_USER_ID, adminOperator()));

        assertEquals(
                ErrorCode.CONFLICT.getCode(),
                ex.getCode(),
                "同一账号 U 绑第二个老人 B → 撞 uk_elder_user → 10005（1:1，绝不 500）");
    }

    // ---------------- helpers ----------------

    private BizException assertThrowsBiz(Runnable action) {
        return org.junit.jupiter.api.Assertions.assertThrows(BizException.class, action::run);
    }

    private Elder elder(Long userId) {
        Elder elder = new Elder();
        elder.setId(ELDER_ID);
        elder.setUserId(userId);
        elder.setDeleted(0);
        return elder;
    }

    private LoginUser adminOperator() {
        return new LoginUser(
                900000000000000002L,
                1L,
                List.of("ORG_ADMIN"),
                List.of("account:org:manage"),
                List.of(1L),
                2);
    }
}
