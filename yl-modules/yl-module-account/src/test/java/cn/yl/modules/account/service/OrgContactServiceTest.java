package cn.yl.modules.account.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yl.api.security.LoginUser;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.account.domain.entity.Org;
import cn.yl.modules.account.dto.OrgContactView;
import cn.yl.modules.account.mapper.OrgMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * {@link OrgContactService} 测试（CR-M2-009 B-1-a）。
 *
 * <p><b>为什么必须有这些断言</b>：本端点的风险不在「能不能返回数据」，而在<b>会不会变成跨机构读面</b> —— {@code org} 表只登记 {@code
 * ORG_COLUMN}，scope=1/5 下第三闸走「未登记 → 放行」暗区，服务层的归属自证是<b>唯一</b>防线。故断言覆盖： 正常返回 / 无所属机构 / <b>归属自证失败</b>
 * / 未登录 / 机构不存在，且错误码必须落在 10002 / 10004。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrgContactServiceTest {

    private static final long USER_ID = 900000000000000003L;
    private static final long ORG_ID = 1L;
    private static final long OTHER_ORG_ID = 2L;

    @Mock private OrgMapper orgMapper;

    private OrgContactService service;

    @BeforeEach
    void setUp() {
        service = new OrgContactService(orgMapper);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void 正常返回所属机构公开联系方式() {
        login(ORG_ID, List.of(ORG_ID));
        when(orgMapper.selectById(ORG_ID)).thenReturn(org(ORG_ID, "XX 颐养中心", "张顾问", "曲靖市麒麟区"));

        OrgContactView view = service.currentContact();

        assertNotNull(view);
        assertEquals(ORG_ID, view.getId());
        assertEquals("XX 颐养中心", view.getOrgName());
        assertEquals("张顾问", view.getContactName());
        assertEquals("曲靖市麒麟区", view.getAddress());
    }

    @Test
    void orgId为空时回退到orgIds首个() {
        login(null, List.of(ORG_ID, OTHER_ORG_ID));
        when(orgMapper.selectById(ORG_ID)).thenReturn(org(ORG_ID, "A", "B", "C"));

        assertEquals(ORG_ID, service.currentContact().getId());
    }

    @Test
    void 无所属机构返回10004() {
        login(null, List.of());

        BizException ex = assertThrows(BizException.class, () -> service.currentContact());
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    /**
     * 归属自证：登录态 {@code orgId} 不在 {@code orgIds} 内 ⇒ 拒（10004）。
     *
     * <p>这是<b>防跨机构读</b>的关键断言 —— 若删掉该分支，装配异常的登录态可直接读到他人机构。
     *
     * <p><b>★ 为什么必须额外断言「不得触达数据层」（2026-10-15 突变反证实证）</b>：仅断言 {@code code=10004} <b>没有判别力</b> ——
     * 把归属自证分支整段删掉后，流程会掉到 {@code selectById} 返回 {@code null} 的兜底分支， <b>同样抛 10004</b>（同码不泄露存在性口径），测试仍
     * 6/0/0 全绿。故本用例改为<b>行为级判据</b>： 归属自证失败时 mapper <b>一次都不许被调用</b>；消息内容保持与「机构不存在」一致，不额外泄露。
     */
    @Test
    void orgId不在orgIds内视为越权返回10004() {
        login(OTHER_ORG_ID, List.of(ORG_ID));

        BizException ex = assertThrows(BizException.class, () -> service.currentContact());
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
        verify(orgMapper, never()).selectById(anyLong());
    }

    @Test
    void 机构不存在返回10004() {
        login(ORG_ID, List.of(ORG_ID));
        when(orgMapper.selectById(ORG_ID)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.currentContact());
        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
        // 正向对照：「机构不存在」是**查过库之后**才判的，与归属自证失败形成行为级区分
        verify(orgMapper).selectById(ORG_ID);
    }

    @Test
    void 未登录返回10002() {
        SecurityContextHolder.clearContext();

        BizException ex = assertThrows(BizException.class, () -> service.currentContact());
        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
    }

    private void login(Long orgId, List<Long> orgIds) {
        LoginUser user =
                new LoginUser(
                        USER_ID, orgId, List.of("ELDER"), List.of("org:contact:read"), orgIds, 1);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }

    private Org org(Long id, String name, String contact, String address) {
        Org o = new Org();
        o.setId(id);
        o.setOrgName(name);
        o.setContactName(contact);
        o.setAddress(address);
        return o;
    }
}
