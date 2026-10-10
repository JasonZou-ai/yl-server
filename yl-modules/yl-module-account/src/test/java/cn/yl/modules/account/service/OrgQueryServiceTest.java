package cn.yl.modules.account.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import cn.yl.common.api.PageResult;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.account.domain.entity.Org;
import cn.yl.modules.account.dto.OrgQuery;
import cn.yl.modules.account.dto.OrgView;
import cn.yl.modules.account.mapper.OrgMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** R6 机构查询服务测试：分页、字段映射、详情越界同码（不含联系方式密文）。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrgQueryServiceTest {

    @Mock private OrgMapper orgMapper;

    private OrgQueryService service;

    @BeforeEach
    void setUp() {
        service = new OrgQueryService(orgMapper);
    }

    @Test
    void listReturnsPageWithMappedFields() {
        Org org = sampleOrg();
        Page<Org> page = new Page<>(1, 10);
        page.setRecords(List.of(org));
        page.setTotal(1L);
        when(orgMapper.selectPage(any(), any())).thenReturn(page);

        PageResult<OrgView> result = service.list(new OrgQuery());

        assertEquals(1L, result.getTotal());
        assertEquals(1, result.getList().size());
        OrgView view = result.getList().get(0);
        assertEquals("ORG-001", view.getOrgCode());
        assertEquals("昆明市五华区养老院", view.getOrgName());
        assertEquals("张院长", view.getContactName());
    }

    @Test
    void detailMapsFields() {
        when(orgMapper.selectById(1L)).thenReturn(sampleOrg());

        OrgView view = service.detail(1L);

        assertEquals(1L, view.getId());
        assertEquals("昆明市五华区养老院", view.getOrgName());
    }

    /** 详情不存在 / 越界被第三闸过滤 → 同码 10004（不泄露存在性）。 */
    @Test
    void detailNotFoundUsesSameCode() {
        when(orgMapper.selectById(999L)).thenReturn(null);

        BizException ex = assertThrows(BizException.class, () -> service.detail(999L));

        assertEquals(ErrorCode.NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    void detailRejectsMissingOrgId() {
        BizException ex = assertThrows(BizException.class, () -> service.detail(null));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }

    private Org sampleOrg() {
        Org org = new Org();
        org.setId(1L);
        org.setOrgCode("ORG-001");
        org.setOrgName("昆明市五华区养老院");
        org.setOrgType(1);
        org.setRegionCode("530102");
        org.setAddress("云南省昆明市五华区");
        org.setContactName("张院长");
        org.setContactPhoneEnc("encrypted-phone");
        org.setStatus(1);
        return org;
    }
}
