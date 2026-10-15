package cn.yl.modules.evaluation.controller;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import cn.yl.api.security.Logical;
import cn.yl.api.security.PermissionCode;
import cn.yl.api.security.RequiresPermission;
import cn.yl.common.api.PageQuery;
import cn.yl.common.api.PageResult;
import cn.yl.common.api.R;
import cn.yl.modules.evaluation.dto.ReportListItem;
import cn.yl.modules.evaluation.service.AssessmentReportService;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 本人报告列表控制器测试（rR1a6L B5-后端：{@code GET /api/v1/evaluation/reports}）。
 *
 * <p>钉两件事：① 端点本体声明 {@code report:view}（与契约 {@code x-required-permission} 同源；摘注解/换码即红， 这是「无
 * report:view ⇒ 20003」的机械前提，行为侧回归见 {@code ReportListSecondGateTest}）；② 委托与分页透传。
 */
@ExtendWith(MockitoExtension.class)
class ReportListControllerTest {

    @Mock private AssessmentReportService reportService;

    private ReportListController controller;

    @BeforeEach
    void setUp() {
        controller = new ReportListController(reportService);
    }

    @Test
    void listRequiresReportView() throws Exception {
        Method list = ReportListController.class.getMethod("list", PageQuery.class);
        RequiresPermission rp = list.getAnnotation(RequiresPermission.class);

        assertNotNull(rp, "报告列表端点必须声明 @RequiresPermission");
        assertArrayEquals(
                new String[] {PermissionCode.REPORT_VIEW},
                rp.value(),
                "报告列表端点权限码须为 report:view（2001，五角色已授予）");
        assertEquals(Logical.ALL, rp.logical(), "单权限码应为 ALL 语义");
    }

    @Test
    void listDelegatesToServiceAndWrapsAsPageResult() {
        PageQuery query = new PageQuery();
        query.setPageNum(2);
        query.setPageSize(10);
        PageResult<ReportListItem> expected = PageResult.of(List.of(item()), 1L, 2, 10);
        when(reportService.listReports(query)).thenReturn(expected);

        R<PageResult<ReportListItem>> resp = controller.list(query);

        assertEquals(0, resp.getCode());
        assertEquals(expected, resp.getData());
        verify(reportService).listReports(query);
    }

    private ReportListItem item() {
        ReportListItem item = new ReportListItem();
        item.setReportNo("RP-TEST");
        return item;
    }
}
