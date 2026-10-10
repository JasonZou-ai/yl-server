package cn.yl.modules.archive.service;

import cn.yl.common.api.PageResult;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.archive.domain.entity.ElderFamilyBind;
import cn.yl.modules.archive.dto.CarePlanRow;
import cn.yl.modules.archive.dto.FamilyBindingQuery;
import cn.yl.modules.archive.dto.FamilyCarePlanView;
import cn.yl.modules.archive.dto.FamilyReportDetailView;
import cn.yl.modules.archive.dto.FamilyReportView;
import cn.yl.modules.archive.dto.FamilyTrendPointView;
import cn.yl.modules.archive.dto.ReportSourceRow;
import cn.yl.modules.archive.mapper.ElderFamilyBindMapper;
import cn.yl.modules.archive.mapper.FamilyCarePlanSourceMapper;
import cn.yl.modules.archive.mapper.FamilyReportSourceMapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 家属端只读查询服务（D-4）：报告列表 / 报告详情 / 能力趋势 / 照护方案。
 *
 * <p><b>收口口径（唯一入口 {@link #requireMyActiveBinding}）</b>：所有读操作先经 {@code relationId} 取回绑定行；第三闸按角色行级收口
 * （FAMILY → {@code family_user_id = me}；ORG_ADMIN → 经 {@code elder.org_id}）。<b>查不到 / 未生效 / 已过期一律 同码
 * {@link ErrorCode#NOT_FOUND}（10004）</b>，不泄露资源存在性。
 *
 * <p><b>可见性</b>：仅 {@code eval_report.status = 1}（已发布）与 {@code care_plan.status = 1}（已确认）可读；跨域取数经
 * <b>模块自持只读投影 Mapper</b>（手写 SQL，逐列照 {@code 02_schema.sql}），不引用兄弟模块实体。
 *
 * <p><b>不造数</b>：{@code care_plan.items_json} 用 Jackson 解为字符串列表；空串 / 非法 JSON / 非数组 →
 * <b>空列表</b>，绝不抛错或 伪造条目。无已确认方案时返回<b>空方案</b>（HTTP 200 + {@code code=0}）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FamilyReportQueryService {

    /** {@code eval_report.status}：1-有效（已发布）。 */
    private static final int REPORT_STATUS_PUBLISHED = 1;

    /** {@code elder_family_bind.status}：2-生效。 */
    private static final int BIND_STATUS_ACTIVE = 2;

    private static final int DELETED_NO = 0;

    private static final int DEFAULT_PAGE_NUM = 1;

    private static final int DEFAULT_PAGE_SIZE = 10;

    private final ElderFamilyBindMapper bindMapper;

    private final FamilyReportSourceMapper reportMapper;

    private final FamilyCarePlanSourceMapper carePlanMapper;

    /**
     * 照护条目解析器为纯字符串结构（{@code title/content/source}），无日期/多态类型；自建 {@link ObjectMapper} 既避免注入外部可变对象
     * （SpotBugs EI_EXPOSE_REP2），也不依赖容器序列化配置（沿用 {@code CarePlanService} 范式）。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 我的绑定关系下<b>已发布</b>报告列表（分页，{@code published_at} 倒序）。
     *
     * @param relationId 绑定关系 id（第三闸权限键）
     * @param query 分页入参（可空）
     * @return 分页报告视图
     */
    public PageResult<FamilyReportView> reports(Long relationId, FamilyBindingQuery query) {
        ElderFamilyBind bind = requireMyActiveBinding(relationId);
        FamilyBindingQuery q = query == null ? new FamilyBindingQuery() : query;
        int pageNum =
                q.getPageNum() == null || q.getPageNum() < 1 ? DEFAULT_PAGE_NUM : q.getPageNum();
        int pageSize =
                q.getPageSize() == null || q.getPageSize() < 1
                        ? DEFAULT_PAGE_SIZE
                        : q.getPageSize();
        Page<ReportSourceRow> page =
                reportMapper.selectPublishedPage(new Page<>(pageNum, pageSize), bind.getElderId());
        List<FamilyReportView> list =
                page.getRecords().stream().map(FamilyReportQueryService::toReportView).toList();
        return PageResult.of(list, page.getTotal(), pageNum, pageSize);
    }

    /**
     * 报告详情（含维度得分 + 照护条目）。
     *
     * <p><b>越界同码</b>：{@code reportId} 不存在 / 未发布 / 不属于该绑定老人 → 一律 {@code 10004}。
     *
     * @param relationId 绑定关系 id
     * @param reportId 报告 id
     * @return 报告详情视图
     */
    public FamilyReportDetailView detail(Long relationId, Long reportId) {
        ElderFamilyBind bind = requireMyActiveBinding(relationId);
        ReportSourceRow row = reportMapper.selectReportById(reportId);
        if (row == null
                || !Integer.valueOf(REPORT_STATUS_PUBLISHED).equals(row.getStatus())
                || !Objects.equals(row.getElderId(), bind.getElderId())) {
            throw new BizException(ErrorCode.NOT_FOUND, "报告不存在或不属于该绑定老人");
        }
        FamilyReportDetailView view = new FamilyReportDetailView();
        view.setReportId(row.getReportId());
        view.setReportNo(row.getReportNo());
        view.setElderId(row.getElderId());
        view.setGradeCode(row.getGradeCode());
        view.setLevelName(resolveLevelName(row.getFinalLevelName(), row.getPreliminaryLevelName()));
        view.setTotalScore(row.getTotalScore());
        view.setPublishedAt(row.getPublishedAt());
        view.setDimensionScores(reportMapper.selectDimensionScores(reportId));
        view.setCareItems(loadCareItems(bind.getElderId()));
        return view;
    }

    /**
     * 能力变化趋势（{@code published_at} 升序；仅已发布报告）。
     *
     * @param relationId 绑定关系 id
     * @return 趋势点列表
     */
    public List<FamilyTrendPointView> trend(Long relationId) {
        ElderFamilyBind bind = requireMyActiveBinding(relationId);
        return reportMapper.selectTrendPoints(bind.getElderId()).stream()
                .map(FamilyReportQueryService::toTrendPoint)
                .toList();
    }

    /**
     * 最新<b>已确认</b>照护方案（{@code care_plan.status = 1}）。
     *
     * <p>无已确认方案 → <b>空方案</b>（{@code planId=null}、{@code items=[]}、HTTP 200 + {@code code=0}），<b>不返
     * 10004</b>（"尚未生成"非越权，走前端空态）。
     *
     * @param relationId 绑定关系 id
     * @return 照护方案视图（可能为空方案）
     */
    public FamilyCarePlanView carePlan(Long relationId) {
        ElderFamilyBind bind = requireMyActiveBinding(relationId);
        CarePlanRow plan = carePlanMapper.selectConfirmedPlan(bind.getElderId());
        FamilyCarePlanView view = new FamilyCarePlanView();
        view.setElderId(bind.getElderId());
        if (plan == null) {
            view.setItems(List.of());
            return view;
        }
        view.setPlanId(plan.getId());
        view.setReportId(plan.getReportId());
        view.setGradeCode(plan.getGradeCode());
        view.setConfirmedAt(plan.getConfirmedAt());
        view.setItems(parseItems(plan.getItemsJson()));
        return view;
    }

    /**
     * 取"我"生效中的绑定行（唯一收口；越权/未生效/过期 → 同码 {@code 10004}）。
     *
     * <p>第三闸按登录态 {@code dataScope} 行级收口：FAMILY 仅能取到 {@code family_user_id = me} 的行；ORG_ADMIN 经
     * {@code elder.org_id} 反查锚收口 —— 故越权绑定自然"查不到"。
     */
    private ElderFamilyBind requireMyActiveBinding(Long relationId) {
        if (relationId == null) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        ElderFamilyBind bind = bindMapper.selectById(relationId);
        if (bind == null
                || !Integer.valueOf(BIND_STATUS_ACTIVE).equals(bind.getStatus())
                || !Integer.valueOf(DELETED_NO).equals(bind.getDeleted())
                || isExpired(bind)) {
            throw new BizException(ErrorCode.NOT_FOUND);
        }
        return bind;
    }

    /** 授权是否已过期（{@code auth_end_at < now}；无截止时间视为长期有效）。 */
    private boolean isExpired(ElderFamilyBind bind) {
        LocalDateTime end = bind.getAuthEndAt();
        return end != null && end.isBefore(LocalDateTime.now());
    }

    /** 取该老人最新已确认方案的照护条目（空态 → 空列表，不造数）。 */
    private List<String> loadCareItems(Long elderId) {
        CarePlanRow plan = carePlanMapper.selectConfirmedPlan(elderId);
        if (plan == null) {
            return List.of();
        }
        return parseItems(plan.getItemsJson());
    }

    /**
     * {@code care_plan.items_json} → 字符串列表。
     *
     * <p>结构为 {@code [{"title","content","source"}]}。渲染口径：{@code
     * title：content}（仅其一存在时取其一；纯字符串元素原样保留）； 空串 / 非法 JSON / 非数组 → <b>空列表</b>（异常仅记日志，不抛出，<b>不造数</b>）。
     */
    private List<String> parseItems(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            log.warn("照护条目 JSON 解析失败，按空列表返回（不造数）：{}", e.getMessage());
            return List.of();
        }
        if (root == null || !root.isArray()) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (JsonNode element : root) {
            String rendered = renderItem(element);
            if (rendered != null && !rendered.isBlank()) {
                items.add(rendered);
            }
        }
        return items;
    }

    /** 单条照护条目 → 展示串（无法渲染 → {@code null}，由调用方跳过）。 */
    private String renderItem(JsonNode element) {
        if (element == null || element.isNull()) {
            return null;
        }
        if (element.isTextual()) {
            return element.asText();
        }
        String title = textField(element, "title");
        String content = textField(element, "content");
        if (title != null && content != null) {
            return title + "：" + content;
        }
        return title != null ? title : content;
    }

    /** 读对象字段为去空白非空串；缺失 / 非文本 / 空白 → {@code null}。 */
    private String textField(JsonNode element, String field) {
        JsonNode node = element.get(field);
        if (node == null || node.isNull() || !node.isTextual()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** 等级名称：优先终判，缺失回退初判名。 */
    private static String resolveLevelName(String finalLevelName, String preliminaryLevelName) {
        if (finalLevelName != null && !finalLevelName.isBlank()) {
            return finalLevelName;
        }
        return preliminaryLevelName;
    }

    private static FamilyReportView toReportView(ReportSourceRow row) {
        FamilyReportView view = new FamilyReportView();
        view.setReportId(row.getReportId());
        view.setReportNo(row.getReportNo());
        view.setElderId(row.getElderId());
        view.setGradeCode(row.getGradeCode());
        view.setTotalScore(row.getTotalScore());
        view.setPublishedAt(row.getPublishedAt());
        return view;
    }

    private static FamilyTrendPointView toTrendPoint(ReportSourceRow row) {
        FamilyTrendPointView point = new FamilyTrendPointView();
        point.setReportId(row.getReportId());
        point.setPublishedAt(row.getPublishedAt());
        point.setGradeCode(row.getGradeCode());
        point.setLevelName(
                resolveLevelName(row.getFinalLevelName(), row.getPreliminaryLevelName()));
        point.setTotalScore(row.getTotalScore());
        return point;
    }
}
