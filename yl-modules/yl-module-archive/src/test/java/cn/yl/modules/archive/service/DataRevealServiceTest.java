package cn.yl.modules.archive.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.archive.domain.entity.Elder;
import cn.yl.modules.archive.dto.DataRevealRequest;
import cn.yl.modules.archive.dto.DataRevealResponse;
import cn.yl.modules.archive.mapper.ElderMapper;
import cn.yl.modules.archive.support.SensitiveFieldSupport;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** 敏感字段明文查看服务测试（D-2）：字段白名单、bizType 白名单、越界/不存在同码、明文映射。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DataRevealServiceTest {

    private static final long OPERATOR_ID = 900000000000000002L;

    @Mock private ElderMapper mapper;

    @Mock private SensitiveFieldSupport support;

    private DataRevealService service;

    @BeforeEach
    void setUp() {
        service = new DataRevealService(mapper, support);
    }

    /** bizType=USER 本期不支持 → 10001（且不触碰数据库）。 */
    @Test
    void revealRejectsUnsupportedBizType() {
        DataRevealRequest req = new DataRevealRequest();
        req.setBizType("USER");
        req.setBizId(1L);
        req.setFields(List.of("name"));

        BizException ex = assertThrows(BizException.class, () -> service.reveal(req, OPERATOR_ID));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(mapper, times(0)).selectOne(any());
    }

    /** 白名单外字段 → 10001（避免把未登记的密文列当作可导出面）。 */
    @Test
    void revealRejectsUnknownField() {
        DataRevealRequest req = new DataRevealRequest();
        req.setBizType("ELDER");
        req.setBizId(1L);
        req.setFields(List.of("name", "healthHistoryEnc"));

        BizException ex = assertThrows(BizException.class, () -> service.reveal(req, OPERATOR_ID));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
        verify(mapper, times(0)).selectOne(any());
    }

    /** 越界（第三闸过滤）与不存在同为 0 行 → 10004（不泄露存在性）。 */
    @Test
    void revealNotFoundWhenRowInvisibleOrMissing() {
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(null);
        DataRevealRequest req = new DataRevealRequest();
        req.setBizType("ELDER");
        req.setBizId(999L);
        req.setFields(List.of("name"));

        BizException ex = assertThrows(BizException.class, () -> service.reveal(req, OPERATOR_ID));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    /** 白名单字段命中 → 返回对应明文字段（经 Mapper 查询，第三闸介入）。 */
    @Test
    void revealReturnsPlaintextForWhitelistedFields() {
        Elder elder = new Elder();
        elder.setId(1L);
        elder.setNameEnc("encName");
        elder.setIdCardEnc("encIdCard");
        elder.setPhoneEnc("encPhone");
        elder.setAddressEnc("encAddress");
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(elder);
        when(support.decrypt("encName")).thenReturn("张三");
        when(support.decrypt("encIdCard")).thenReturn("530102199003074567");
        when(support.decrypt("encPhone")).thenReturn("13812345678");
        when(support.decrypt("encAddress")).thenReturn("云南省昆明市");

        DataRevealRequest req = new DataRevealRequest();
        req.setBizType("ELDER");
        req.setBizId(1L);
        req.setFields(List.of("name", "idCardNo", "phone", "address"));

        DataRevealResponse response = service.reveal(req, OPERATOR_ID);

        assertEquals("ELDER", response.getBizType());
        assertEquals(1L, response.getBizId());
        assertEquals("张三", response.getFields().get("name"));
        assertEquals("530102199003074567", response.getFields().get("idCardNo"));
        assertEquals("13812345678", response.getFields().get("phone"));
        assertEquals("云南省昆明市", response.getFields().get("address"));
    }

    /** bizType 大小写不敏感（{@code elder} 归一为 {@code ELDER}）。 */
    @Test
    void revealAcceptsLowercaseBizType() {
        Elder elder = new Elder();
        elder.setNameEnc("encName");
        when(mapper.selectOne(any(QueryWrapper.class))).thenReturn(elder);
        when(support.decrypt("encName")).thenReturn("张三");
        DataRevealRequest req = new DataRevealRequest();
        req.setBizType("elder");
        req.setBizId(1L);
        req.setFields(List.of("name"));

        DataRevealResponse response = service.reveal(req, OPERATOR_ID);

        assertEquals("ELDER", response.getBizType());
        assertEquals("张三", response.getFields().get("name"));
    }
}
