package cn.yl.modules.evaluation.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.evaluation.domain.entity.GbRuleMapping;
import cn.yl.modules.evaluation.dto.RuleMappingView;
import cn.yl.modules.evaluation.mapper.GbRuleMappingMapper;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/** B7 规则映射服务测试：字段映射正确 + {@code ruleVersionId} 缺失即拒。 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RuleMappingServiceTest {

    @Mock private GbRuleMappingMapper gbRuleMappingMapper;

    private RuleMappingService service;

    @BeforeEach
    void setUp() {
        service = new RuleMappingService(gbRuleMappingMapper);
    }

    @Test
    void mappingMapsAllFields() {
        GbRuleMapping row = new GbRuleMapping();
        row.setClauseNo("5.1.1");
        row.setClauseTitle("日常生活活动");
        row.setItemCode("101");
        row.setRuleCode("R-101");
        row.setRemark("备注");
        when(gbRuleMappingMapper.selectList(any())).thenReturn(List.of(row));

        List<RuleMappingView> views = service.mapping(1L);

        assertEquals(1, views.size());
        assertEquals("5.1.1", views.get(0).getClauseNo());
        assertEquals("日常生活活动", views.get(0).getClauseTitle());
        assertEquals("101", views.get(0).getItemCode());
        assertEquals("R-101", views.get(0).getRuleCode());
        assertEquals("备注", views.get(0).getRemark());
    }

    @Test
    void mappingReturnsEmptyWhenNoData() {
        when(gbRuleMappingMapper.selectList(any())).thenReturn(List.of());

        assertEquals(List.of(), service.mapping(2L));
    }

    @Test
    void mappingRejectsMissingRuleVersionId() {
        BizException ex = assertThrows(BizException.class, () -> service.mapping(null));

        assertEquals(ErrorCode.PARAM_INVALID.getCode(), ex.getCode());
    }
}
