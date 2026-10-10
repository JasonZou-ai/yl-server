package cn.yl.modules.supervise.service;

import cn.yl.api.security.LoginUser;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.supervise.domain.entity.SuperviseReport;
import cn.yl.modules.supervise.dto.SuperviseAggregateRow;
import cn.yl.modules.supervise.dto.SuperviseReportView;
import cn.yl.modules.supervise.mapper.SuperviseAggregateMapper;
import cn.yl.modules.supervise.mapper.SuperviseReportMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * B10 监管上报服务（{@code POST /api/v1/supervise/report}）。
 *
 * <p>链路：取登录态机构 → 当前自然月（{@code YearMonth.now()}）→ 聚合评估域计数 → payload JSON → 幂等落库 → 写 {@code
 * retain_until}。
 *
 * <p><b>幂等口径</b>：{@code idempotent_key = "SR-" + orgId + "-" + reportMonth}（服务端生成，≤64）。同月同机构重放时命中
 * {@code uk_month_org}（同键亦命中 {@code uk_idem}）→ <b>更新既有行</b>（{@code
 * payload/sent_at/retain_until}），不新增行。
 *
 * <p><b>裁决 3</b>：{@code retain_until} <b>只写值</b>（{@code now + 5 年}，ER-03）；归档/清理归 P2-7 /
 * D-5，<b>不新增</b> {@code @Scheduled}。
 */
@Slf4j
@Service
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification =
                                "SuperviseReportMapper / SuperviseAggregateMapper / ObjectMapper 均为 Spring 容器管理的"
                                        + "线程安全共享 Bean，构造器注入是标准 DI 用法"))
public class SuperviseReportService {

    private static final int STATUS_SUCCESS = 1;

    /** 监管报表保留期（年），ER-03。 */
    private static final int RETAIN_YEARS = 5;

    private final SuperviseReportMapper reportMapper;
    private final SuperviseAggregateMapper aggregateMapper;
    private final ObjectMapper objectMapper;

    /**
     * 上报当前自然月监管报文。
     *
     * @param operator 登录态操作者（机构取自此处）
     * @return 上报视图
     */
    public SuperviseReportView submit(LoginUser operator) {
        if (operator.orgId() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "当前账号无机构上下文，无法上报（监管跨机构口径待确认）");
        }
        Long orgId = operator.orgId();
        String reportMonth = YearMonth.from(LocalDate.now()).toString();
        SuperviseAggregateRow aggregate = aggregateMapper.aggregate(orgId, reportMonth);
        String payload = toJson(aggregate);
        String idempotentKey = "SR-" + orgId + "-" + reportMonth;
        LocalDateTime now = LocalDateTime.now();

        SuperviseReport existing = findExisting(reportMonth, orgId);
        if (existing != null) {
            existing.setPayload(payload);
            existing.setStatus(STATUS_SUCCESS);
            existing.setRetryCount(0);
            existing.setSentAt(now);
            existing.setRetainUntil(now.plusYears(RETAIN_YEARS));
            existing.setIdempotentKey(idempotentKey);
            reportMapper.updateById(existing);
            return toView(existing);
        }

        SuperviseReport report = new SuperviseReport();
        report.setReportMonth(reportMonth);
        report.setOrgId(orgId);
        report.setPayload(payload);
        report.setIdempotentKey(idempotentKey);
        report.setStatus(STATUS_SUCCESS);
        report.setRetryCount(0);
        report.setSentAt(now);
        report.setRetainUntil(now.plusYears(RETAIN_YEARS));
        reportMapper.insert(report);
        return toView(report);
    }

    private SuperviseReport findExisting(String reportMonth, Long orgId) {
        return reportMapper.selectOne(
                new QueryWrapper<SuperviseReport>()
                        .eq("report_month", reportMonth)
                        .eq("org_id", orgId));
    }

    private String toJson(SuperviseAggregateRow aggregate) {
        try {
            return objectMapper.writeValueAsString(aggregate);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "监管报文序列化失败: " + e.getMessage());
        }
    }

    private SuperviseReportView toView(SuperviseReport report) {
        SuperviseReportView view = new SuperviseReportView();
        view.setId(report.getId());
        view.setReportMonth(report.getReportMonth());
        view.setOrgId(report.getOrgId());
        view.setStatus(report.getStatus());
        view.setSentAt(report.getSentAt());
        return view;
    }
}
