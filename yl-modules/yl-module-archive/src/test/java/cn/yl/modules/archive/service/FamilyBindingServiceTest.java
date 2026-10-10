package cn.yl.modules.archive.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yl.api.security.LoginUser;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.common.ratelimit.RateLimiter;
import cn.yl.modules.archive.domain.entity.ElderFamilyBind;
import cn.yl.modules.archive.dto.FamilyBindingCreateRequest;
import cn.yl.modules.archive.dto.FamilyBindingView;
import cn.yl.modules.archive.mapper.ElderFamilyBindMapper;
import cn.yl.modules.archive.mapper.ElderMapper;
import cn.yl.modules.archive.mapper.FamilyBindingVerifyMapper;
import cn.yl.modules.archive.support.SensitiveFieldSupport;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * 亲情绑定状态机服务测试（D-4）——★C2 宽容化 + ★F2 核验 + 频控 + 未装配直拒 + ★R-2 重新申请复用活性行。
 *
 * <p>覆盖设计 §3.3 机械验收断言：{@code applyLandsPendingElderWhenElderHasAccount} / {@code
 * applyLandsPendingOrgWhenElderHasNoAccount} / {@code approveAcceptsPendingElder} / {@code
 * approveAcceptsPendingOrg} / {@code applyRejectsWhenCodecUnavailable}。
 *
 * <p><b>★R-2 断言</b>：拒绝后 / 解绑后重新申请 → {@code code=0} 且 {@code status ∈ {0,1}}（<b>不是</b> {@code
 * 10005}）、 <b>复用原活性行</b>；{@code status ∈ {0,1,2}} 重复申请 → {@code 10005}。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FamilyBindingServiceTest {

    private static final long FAMILY_ID = 900000000000000004L;
    private static final long ROW_ID = 7L;

    @Mock private ElderFamilyBindMapper bindMapper;
    @Mock private FamilyBindingVerifyMapper verifyMapper;
    @Mock private ElderMapper elderMapper;
    @Mock private SensitiveFieldSupport support;
    @Mock private RateLimiter rateLimiter;

    private FamilyBindingService service;

    @BeforeEach
    void setUp() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(true);
        when(support.isAvailable()).thenReturn(true);
        when(support.hashForSearch(anyString())).thenReturn("hash-64");
        // 默认无同对活性行（insert 分支）；需要复用/重复的分支在各自用例内覆盖。
        when(bindMapper.selectOne(any())).thenReturn(null);
        service =
                new FamilyBindingService(
                        bindMapper, verifyMapper, elderMapper, support, rateLimiter);
    }

    // ---------------- ★C2：create 落态分派（§3.3 判定规则） ----------------

    @Test
    void applyLandsPendingElderWhenElderHasAccount() {
        when(verifyMapper.countExactMatch(anyLong(), anyString(), anyString())).thenReturn(1);
        when(verifyMapper.hasLoginAccount(anyLong())).thenReturn(true);

        service.apply(request(), familyOperator());

        ElderFamilyBind inserted = capturedInsert();
        assertEquals(0, inserted.getStatus(), "老人已注册 → 待老人确认（status=0）");
        assertEquals(FAMILY_ID, inserted.getFamilyUserId(), "familyUserId 取登录态");
        assertEquals(0, inserted.getDeleted());
    }

    @Test
    void applyLandsPendingOrgWhenElderHasNoAccount() {
        when(verifyMapper.countExactMatch(anyLong(), anyString(), anyString())).thenReturn(1);
        when(verifyMapper.hasLoginAccount(anyLong())).thenReturn(false);

        service.apply(request(), familyOperator());

        assertEquals(1, capturedInsert().getStatus(), "老人未注册 → 待机构审核（status=1），避免死路");
    }

    // ---------------- ★R-2：重新申请复用活性行（status ∈ {3,4} → 0/1，不新增行） ----------------

    @Test
    void applyReusesRejectedRowAndResetsToPending() {
        when(verifyMapper.countExactMatch(anyLong(), anyString(), anyString())).thenReturn(1);
        when(verifyMapper.hasLoginAccount(anyLong())).thenReturn(true);
        when(bindMapper.selectOne(any())).thenReturn(bind(ROW_ID, 3)); // 已拒绝

        FamilyBindingView view = service.apply(request(), familyOperator());

        assertEquals(0, view.getStatus(), "拒绝后重新申请 → status∈{0,1}（有账号→0），绝不 10005");
        assertEquals(ROW_ID, view.getId(), "复用原活性行（id 不变）");
        assertNull(view.getRejectReason(), "reject_reason 复位为 NULL");
        assertResetWrapperOnlyTouchesExpectedColumns();
        verify(bindMapper, never()).insert(any(ElderFamilyBind.class));
    }

    @Test
    void applyReusesUnboundRowAndResetsToPending() {
        when(verifyMapper.countExactMatch(anyLong(), anyString(), anyString())).thenReturn(1);
        when(verifyMapper.hasLoginAccount(anyLong())).thenReturn(false);
        when(bindMapper.selectOne(any())).thenReturn(bind(ROW_ID, 4)); // 已解绑

        FamilyBindingView view = service.apply(request(), familyOperator());

        assertEquals(1, view.getStatus(), "解绑后重新申请 → status∈{0,1}（无账号→1），绝不 10005");
        assertEquals(ROW_ID, view.getId(), "复用原活性行（id 不变）");
        assertResetWrapperOnlyTouchesExpectedColumns();
        verify(bindMapper, never()).insert(any(ElderFamilyBind.class));
    }

    /**
     * 复位 UPDATE 的 SET 子句只碰 status/reject_reason/verify_method/proof_cos_key，且 reject_reason 以 NULL
     * 入参。
     */
    @SuppressWarnings("unchecked")
    private void assertResetWrapperOnlyTouchesExpectedColumns() {
        ArgumentCaptor<UpdateWrapper<ElderFamilyBind>> captor =
                ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(bindMapper).update(isNull(), captor.capture());
        UpdateWrapper<ElderFamilyBind> reset = captor.getValue();
        String set = reset.getSqlSet();
        assertTrue(set.contains("status="), "SET 须含 status：" + set);
        assertTrue(set.contains("reject_reason="), "SET 须含 reject_reason（显式置 NULL）：" + set);
        assertTrue(set.contains("verify_method="), "SET 须含 verify_method：" + set);
        assertTrue(set.contains("proof_cos_key="), "SET 须含 proof_cos_key：" + set);
        assertFalse(set.contains("auth_start_at"), "auth_start_at 不得被覆盖：" + set);
        assertFalse(set.contains("auth_end_at"), "auth_end_at 不得被覆盖：" + set);
        assertTrue(
                reset.getParamNameValuePairs().values().stream().anyMatch(Objects::isNull),
                "reject_reason 须以 NULL 入参（updateById 会忽略 null，故必须用 UpdateWrapper 显式 set）");
    }

    // ---------------- ★C2 宽容化：approve 认 {0,1} → 2 ----------------

    @Test
    void approveAcceptsPendingElder() {
        when(bindMapper.selectOne(any())).thenReturn(bind(1L, 0));

        FamilyBindingView view = service.approve(1L, adminOperator());

        assertEquals(2, view.getStatus(), "★C2：status=0（待老人确认）也必须能被机构审核放行（0→2）");
        verify(bindMapper).updateById(any(ElderFamilyBind.class));
    }

    @Test
    void approveAcceptsPendingOrg() {
        when(bindMapper.selectOne(any())).thenReturn(bind(1L, 1));

        FamilyBindingView view = service.approve(1L, adminOperator());

        assertEquals(2, view.getStatus());
    }

    @Test
    void approveRejectsIllegalState() {
        when(bindMapper.selectOne(any())).thenReturn(bind(1L, 3));

        BizException ex =
                assertThrows(BizException.class, () -> service.approve(1L, adminOperator()));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    // ---------------- ★F2 核验 / 频控 / 未装配 ----------------

    @Test
    void applyRejectsWhenCodecUnavailable() {
        when(support.isAvailable()).thenReturn(false);

        BizException ex =
                assertThrows(BizException.class, () -> service.apply(request(), familyOperator()));

        assertEquals(ErrorCode.ARCHIVE_SENSITIVE_ENCRYPT_FAILED.getCode(), ex.getCode());
        verify(bindMapper, never()).insert(any(ElderFamilyBind.class));
    }

    @Test
    void applyRejectsWhenExactMatchIsZero() {
        when(verifyMapper.countExactMatch(anyLong(), anyString(), anyString())).thenReturn(0);

        BizException ex =
                assertThrows(BizException.class, () -> service.apply(request(), familyOperator()));

        // 与"老人不存在"同码，不泄露存在性
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
        verify(bindMapper, never()).insert(any(ElderFamilyBind.class));
    }

    @Test
    void applyRejectsWhenRateLimited() {
        when(rateLimiter.tryAcquire(anyString(), anyInt(), anyInt())).thenReturn(false);

        BizException ex =
                assertThrows(BizException.class, () -> service.apply(request(), familyOperator()));

        assertEquals(ErrorCode.RATE_LIMITED.getCode(), ex.getCode());
    }

    /**
     * ★R-2 承重：复用分支<b>不得跳过核验</b> —— 即便存在可复用的活性行（status=3），核验计数为 0（申请人已换人/身份不符）时仍须 {@code
     * 10004}，且不触发任何 UPDATE / INSERT。
     */
    @Test
    void applyStillVerifiesWhenReusableRowExists() {
        when(verifyMapper.countExactMatch(anyLong(), anyString(), anyString())).thenReturn(0);
        when(bindMapper.selectOne(any())).thenReturn(bind(ROW_ID, 3));

        BizException ex =
                assertThrows(BizException.class, () -> service.apply(request(), familyOperator()));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode(), "复用分支不得跳过核验");
        verify(bindMapper, never()).insert(any(ElderFamilyBind.class));
        verify(bindMapper, never()).update(any(), any());
    }

    @Test
    void applyRejectsUnknownVerifyMethod() {
        FamilyBindingCreateRequest req = request();
        req.setVerifyMethod("EMAIL");

        BizException ex =
                assertThrows(BizException.class, () -> service.apply(req, familyOperator()));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    /** 重复申请（已有 status=0 待老人确认）→ 10005。 */
    @Test
    void applyRejectsDuplicateBinding() {
        when(verifyMapper.countExactMatch(anyLong(), anyString(), anyString())).thenReturn(1);
        when(verifyMapper.hasLoginAccount(anyLong())).thenReturn(true);
        when(bindMapper.selectOne(any())).thenReturn(bind(ROW_ID, 0));

        BizException ex =
                assertThrows(BizException.class, () -> service.apply(request(), familyOperator()));

        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
        verify(bindMapper, never()).insert(any(ElderFamilyBind.class));
    }

    /** 重复申请（已有 status=1 待机构审核）→ 10005。 */
    @Test
    void applyRejectsDuplicateWhenPendingOrg() {
        when(verifyMapper.countExactMatch(anyLong(), anyString(), anyString())).thenReturn(1);
        when(verifyMapper.hasLoginAccount(anyLong())).thenReturn(false);
        when(bindMapper.selectOne(any())).thenReturn(bind(ROW_ID, 1));

        BizException ex =
                assertThrows(BizException.class, () -> service.apply(request(), familyOperator()));

        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
        verify(bindMapper, never()).insert(any(ElderFamilyBind.class));
        verify(bindMapper, never()).update(any(), any());
    }

    /** status=2（已生效）时重复申请 → 10005（不覆盖生效绑定）。 */
    @Test
    void applyRejectsWhenAlreadyActive() {
        when(verifyMapper.countExactMatch(anyLong(), anyString(), anyString())).thenReturn(1);
        when(verifyMapper.hasLoginAccount(anyLong())).thenReturn(true);
        when(bindMapper.selectOne(any())).thenReturn(bind(ROW_ID, 2));

        BizException ex =
                assertThrows(BizException.class, () -> service.apply(request(), familyOperator()));

        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
        verify(bindMapper, never()).insert(any(ElderFamilyBind.class));
        verify(bindMapper, never()).update(any(), any());
    }

    // ---------------- reject / unbind ----------------

    @Test
    void rejectFromPendingSetsRejected() {
        when(bindMapper.selectOne(any())).thenReturn(bind(1L, 0));

        FamilyBindingView view = service.reject(1L, "不同意", elderOperator());

        assertEquals(3, view.getStatus());
        assertEquals("不同意", view.getRejectReason());
    }

    @Test
    void unbindRejectsWhenNotActive() {
        when(bindMapper.selectOne(any())).thenReturn(bind(1L, 1));

        BizException ex =
                assertThrows(BizException.class, () -> service.unbind(1L, familyOperator()));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void unbindSetsUnboundKeepingNotDeleted() {
        when(bindMapper.selectOne(any())).thenReturn(bind(1L, 2));

        service.unbind(1L, familyOperator());

        ArgumentCaptor<ElderFamilyBind> captor = ArgumentCaptor.forClass(ElderFamilyBind.class);
        verify(bindMapper).updateById(captor.capture());
        assertEquals(4, captor.getValue().getStatus());
    }

    @Test
    void requireBindingNotFoundWhenMissing() {
        when(bindMapper.selectOne(any())).thenReturn(null);

        BizException ex =
                assertThrows(BizException.class, () -> service.approve(9L, adminOperator()));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    // ---------------- helpers ----------------

    private ElderFamilyBind capturedInsert() {
        ArgumentCaptor<ElderFamilyBind> captor = ArgumentCaptor.forClass(ElderFamilyBind.class);
        verify(bindMapper).insert(captor.capture());
        ElderFamilyBind bind = captor.getValue();
        assertNotNull(bind);
        return bind;
    }

    private FamilyBindingCreateRequest request() {
        FamilyBindingCreateRequest req = new FamilyBindingCreateRequest();
        req.setElderId(500L);
        req.setRelation("CHILD");
        req.setVerifyMethod("SCAN");
        req.setElderName("张伟");
        req.setIdCardNo("530102199003070011");
        return req;
    }

    private ElderFamilyBind bind(long id, int status) {
        ElderFamilyBind bind = new ElderFamilyBind();
        bind.setId(id);
        bind.setElderId(500L);
        bind.setFamilyUserId(FAMILY_ID);
        bind.setRelation("CHILD");
        bind.setVerifyMethod("SCAN");
        bind.setStatus(status);
        bind.setDeleted(0);
        return bind;
    }

    private LoginUser familyOperator() {
        return new LoginUser(
                FAMILY_ID, 1L, List.of("FAMILY"), List.of("account:family:bind"), List.of(1L), 5);
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

    private LoginUser elderOperator() {
        return new LoginUser(
                900000000000000003L,
                1L,
                List.of("ELDER"),
                List.of("account:family:bind:reject"),
                List.of(1L),
                1);
    }
}
