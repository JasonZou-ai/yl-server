package cn.yl.modules.evaluation.controller;

import cn.yl.api.security.PermissionCode;
import cn.yl.api.security.RequiresPermission;
import cn.yl.common.api.PageQuery;
import cn.yl.common.api.PageResult;
import cn.yl.common.api.R;
import cn.yl.modules.evaluation.dto.ReportListItem;
import cn.yl.modules.evaluation.service.AssessmentReportService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 本人/本机构报告列表接口（rR1a6L B5-后端）：{@code GET /api/v1/evaluation/reports}。
 *
 * <p>背景：既有报告读面<strong>全部需要 {@code orderId} / {@code reportNo} 之一</strong>（详情 / PDF /
 * 验真），端侧老人读面因此拿不到清单、只能显式抛错。本端点补上这一格，且<strong>不引入任何新的可见面</strong>： 可见行 = 第三闸按登录态收口的既有行集合（老人 =
 * 本人的报告行）。
 *
 * <p><b>鉴权口径（四道闸）</b>：
 *
 * <ul>
 *   <li><b>第二闸</b>：{@code report:view}（2001，五角色均已授予；可见范围<b>不靠权限点区分</b>）；
 *   <li><b>第三闸</b>：<b>不写任何归属条件</b>——行级范围由 {@code DataScopePermissionHandler} 经 {@code reportMapper}
 *       自动收口（{@code eval_report} 登记在 {@code SELF_VIA_PARENT}，父表 {@code elder.user_id}； 与 {@code
 *       CR-M2-006} 的「读文件绕过数据域」教训相对：本端点必须走 Mapper）；
 *   <li><b>状态闸</b>：服务层与 {@code AssessmentReportService#assertPublishedToSelf} <b>同口径</b>（只 {@code
 *       status=1} 已发布）；
 *   <li><b>第四闸</b>：{@code report:view} 非敏感点，不要求二次验证凭据。
 * </ul>
 *
 * <p><b>为什么单独一个控制器而不是并入 {@link ReportController}</b>：二者同前缀但职责不同 —— {@code ReportController} 是「按
 * reportNo 取文件」的分发面，本控制器是「按登录态列清单」的集合读面；拆开后各自的鉴权段 说明、契约 x-data-scope 与回归断言互不干扰（先例：{@code
 * EvaluationItemController} / {@code GradingController} 同为 {@code /api/v1/evaluation} 前缀下的独立控制器）。
 *
 * <p><b>无构造器级 SpotBugs 豁免</b>：本控制器只注入一个只读服务 Bean 并在方法体内调用，静态分析未报 {@code EI_EXPOSE_REP2} ——
 * 按本项目口径（「不接受多余的豁免」，同 {@code US_USELESS_SUPPRESSION}）<b>不加</b> {@code @SuppressFBWarnings}（实测加即被
 * SpotBugs 判为 {@code US_USELESS_SUPPRESSION_ON_METHOD} 而构建失败）。
 */
@RestController
@RequestMapping("/api/v1/evaluation/reports")
@RequiredArgsConstructor
public class ReportListController {

    private final AssessmentReportService reportService;

    /**
     * 分页查询本人/本机构<b>已发布</b>报告（最小字段集）。
     *
     * <p>错误语义沿用全站口径：业务失败 HTTP 200 + {@code R.code}；无数据返<b>空列表</b>（不泄露存在性）。
     */
    @GetMapping
    @RequiresPermission(PermissionCode.REPORT_VIEW)
    public R<PageResult<ReportListItem>> list(PageQuery query) {
        return R.ok(reportService.listReports(query));
    }
}
