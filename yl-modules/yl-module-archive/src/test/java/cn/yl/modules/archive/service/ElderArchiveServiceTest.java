package cn.yl.modules.archive.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yl.api.security.LoginUser;
import cn.yl.common.api.PageResult;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.archive.domain.entity.Elder;
import cn.yl.modules.archive.dto.ElderCreateRequest;
import cn.yl.modules.archive.dto.ElderQuery;
import cn.yl.modules.archive.dto.ElderView;
import cn.yl.modules.archive.mapper.ElderMapper;
import cn.yl.modules.archive.support.SensitiveFieldSupport;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
 * 档案域服务测试（D-2）：建档加密/去重/唯一键重试、列表软删除过滤、详情越界同码。
 *
 * <p>持久化与编解码器用 Mock（不连库/不需密钥），故本测能固定「<b>落库字段是密文而非明文</b>」 「<b>档案号冲突重试</b>」「<b>查询显式 {@code
 * deleted=0}</b>」等口径。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ElderArchiveServiceTest {

    private static final long OPERATOR_ID = 900000000000000002L;
    private static final String ELDER_NO = "ELD20260924000001";

    @Mock private ElderMapper mapper;

    @Mock private SensitiveFieldSupport support;

    @Mock private ElderNoGenerator noGenerator;

    private ElderArchiveService service;

    @BeforeEach
    void setUp() {
        service = new ElderArchiveService(mapper, support, noGenerator);
    }

    /** 建档：敏感字段经编解码器落库（密文而非明文）+ 生成脱敏串 + 身份取登录态。 */
    @Test
    void createEncryptsAndMasksSensitiveFields() {
        stubCodec();
        when(noGenerator.next()).thenReturn(ELDER_NO);
        when(mapper.selectCount(any())).thenReturn(0L);
        when(mapper.insert(any(Elder.class)))
                .thenAnswer(
                        invocation -> {
                            Elder e = invocation.getArgument(0);
                            e.setId(12345L);
                            return 1;
                        });

        ElderCreateRequest req = request();
        ElderView view = service.create(req, operator());

        ArgumentCaptor<Elder> captor = ArgumentCaptor.forClass(Elder.class);
        verify(mapper).insert(captor.capture());
        Elder saved = captor.getValue();

        assertEquals("enc(张三)", saved.getNameEnc(), "姓名须经编解码器落库");
        assertNotEquals("张三", saved.getNameEnc(), "禁止明文落库");
        assertEquals("enc(530102199003074567)", saved.getIdCardEnc());
        assertEquals("hash(530102199003074567)", saved.getIdCardHash());
        assertEquals("hash(张三)", saved.getNameHash());
        assertEquals("5301**********4567", saved.getIdCardMask());
        assertEquals(ELDER_NO, saved.getElderNo());
        assertEquals(1, saved.getStatus(), "默认在册");
        assertEquals(0, saved.getDeleted());
        assertEquals(OPERATOR_ID, saved.getCreatedBy(), "建档人取登录态");
        assertEquals(1L, saved.getOrgId());

        assertEquals(12345L, view.getId());
        assertEquals("张*", view.getName(), "返回视图须脱敏");
        assertEquals("5301**********4567", view.getIdCardMask());
    }

    /** 身份证号在 deleted=0 范围内去重：命中已有摘要 → 10005。 */
    @Test
    void createRejectsDuplicateIdCard() {
        when(support.hashForSearch(anyString())).thenReturn("hash(530102199003074567)");
        when(mapper.selectCount(any())).thenReturn(1L);

        BizException ex =
                assertThrows(BizException.class, () -> service.create(request(), operator()));

        assertEquals(ErrorCode.CONFLICT.getCode(), ex.getCode());
        verify(mapper, times(0)).insert(any(Elder.class));
    }

    /** 编解码器未装配（dev 无密钥）→ 拒绝明文落库（40001），不插入。 */
    @Test
    void createFailsWhenCodecUnavailable() {
        when(support.encrypt(anyString()))
                .thenThrow(new BizException(ErrorCode.ARCHIVE_SENSITIVE_ENCRYPT_FAILED));

        ElderCreateRequest req = new ElderCreateRequest();
        req.setName("张三");
        req.setOrgId(1L);

        BizException ex = assertThrows(BizException.class, () -> service.create(req, operator()));

        assertEquals(ErrorCode.ARCHIVE_SENSITIVE_ENCRYPT_FAILED.getCode(), ex.getCode());
        verify(mapper, times(0)).insert(any(Elder.class));
    }

    /** 档案号唯一键冲突时换号重试（最多 3 次），最终成功。 */
    @Test
    void createRetriesElderNoOnConflict() {
        stubCodec();
        when(noGenerator.next()).thenReturn("ELD-A", "ELD-B");
        when(mapper.selectCount(any())).thenReturn(0L);
        AtomicInteger calls = new AtomicInteger();
        when(mapper.insert(any(Elder.class)))
                .thenAnswer(
                        invocation -> {
                            if (calls.incrementAndGet() == 1) {
                                throw new DuplicateKeyException("duplicate elder_no");
                            }
                            Elder e = invocation.getArgument(0);
                            e.setId(1L);
                            return 1;
                        });

        service.create(request(), operator());

        ArgumentCaptor<Elder> captor = ArgumentCaptor.forClass(Elder.class);
        verify(mapper, times(2)).insert(captor.capture());
        assertEquals("ELD-B", captor.getValue().getElderNo(), "冲突后应换号重试");
    }

    /** 列表：显式 deleted=0 + 姓名 hash 精确 / 档案号前缀，结果脱敏。 */
    @Test
    void listQueriesNonDeletedAndFiltersByKeyword() {
        when(support.isAvailable()).thenReturn(true);
        when(support.hashForSearch("张")).thenReturn("hash(张)");
        Elder e = new Elder();
        e.setId(1L);
        e.setElderNo(ELDER_NO);
        e.setNameEnc("enc(张三)");
        e.setGender(1);
        e.setStatus(1);
        e.setOrgId(1L);
        when(support.decrypt("enc(张三)")).thenReturn("张三");
        when(support.maskName("张三")).thenReturn("张*");
        Page<Elder> page = new Page<>(1, 10);
        page.setRecords(List.of(e));
        page.setTotal(1);
        doReturn(page).when(mapper).selectPage(any(), any());

        ElderQuery query = new ElderQuery();
        query.setKeyword("张");
        PageResult<ElderView> result = service.list(query);

        assertEquals(1, result.getList().size());
        assertEquals("张*", result.getList().get(0).getName());
        assertEquals(1L, result.getTotal());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<Elder>> captor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(mapper).selectPage(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment();
        assertTrue(sql.contains("deleted"), "查询必须显式 deleted=0：" + sql);
        assertTrue(sql.contains("elder_no"), "关键词须含档案号前缀匹配：" + sql);
    }

    /** 详情：命中 → 脱敏视图。 */
    @Test
    void detailReturnsMaskedView() {
        Elder e = new Elder();
        e.setId(1L);
        e.setElderNo(ELDER_NO);
        e.setNameEnc("enc(张三)");
        e.setPhoneEnc("enc(13812345678)");
        e.setStatus(1);
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(e);
        when(support.decrypt("enc(张三)")).thenReturn("张三");
        when(support.maskName("张三")).thenReturn("张*");
        when(support.decrypt("enc(13812345678)")).thenReturn("13812345678");
        when(support.maskPhone("13812345678")).thenReturn("138****5678");

        ElderView view = service.detail(1L);

        assertEquals("张*", view.getName());
        assertEquals("138****5678", view.getPhone());
    }

    /** 详情：查不到（不存在或越界被第三闸过滤）→ 10004（同码，不泄露存在性）。 */
    @Test
    void detailNotFoundThrows10004() {
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.detail(999L));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    private void stubCodec() {
        when(support.encrypt(anyString())).thenAnswer(inv -> "enc(" + inv.getArgument(0) + ")");
        when(support.hashForSearch(anyString()))
                .thenAnswer(inv -> "hash(" + inv.getArgument(0) + ")");
        when(support.maskIdCard(anyString())).thenReturn("5301**********4567");
        when(support.maskName(anyString())).thenReturn("张*");
        when(support.decrypt(anyString())).thenAnswer(inv -> inv.getArgument(0));
    }

    private ElderCreateRequest request() {
        ElderCreateRequest req = new ElderCreateRequest();
        req.setName("张三");
        req.setIdCardNo("530102199003074567");
        req.setGender(1);
        req.setBirthDate(LocalDate.of(1990, 3, 7));
        req.setPhone("13812345678");
        req.setAddress("云南省昆明市");
        req.setOrgId(1L);
        return req;
    }

    private LoginUser operator() {
        return new LoginUser(OPERATOR_ID, 1L, List.of("ASSESSOR"), List.of(), List.of(1L), 2);
    }
}
