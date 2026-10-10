package cn.yl.modules.evaluation.service;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.evaluation.domain.entity.CarePlan;
import cn.yl.modules.evaluation.domain.entity.CareTemplate;
import cn.yl.modules.evaluation.domain.entity.EvalReport;
import cn.yl.modules.evaluation.dto.CarePlanItem;
import cn.yl.modules.evaluation.dto.CarePlanResponse;
import cn.yl.modules.evaluation.mapper.CarePlanMapper;
import cn.yl.modules.evaluation.mapper.CareTemplateMapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 照护方案服务（PRD P02「照护建议」）：按能力等级匹配模板生成方案，评估员建议 → 管理员确认。
 *
 * <p>匹配口径：{@code care_template.grade_code} 与报告 {@code grade_code}（GRADE_*）一致即命中，按 {@code sort_no}
 * 排序；<b>无匹配模板时降级通用建议</b>（PRD P02 异常/边界：「无匹配模板显示通用建议」），绝不返回空建议。
 *
 * <p>确认环节强制双人：{@code confirmedBy} 必须 ≠ {@code suggestBy}（评估员建议、管理员确认），避免单人闭环。
 */
@Service
@RequiredArgsConstructor
public class CarePlanService {

    private static final int PLAN_PENDING = 0;
    private static final int PLAN_CONFIRMED = 1;
    private static final String SOURCE_TEMPLATE = "TEMPLATE";
    private static final String SOURCE_GENERAL = "GENERAL";

    private final CarePlanMapper planMapper;
    private final CareTemplateMapper templateMapper;
    private final AssessmentReportService reportService;

    /**
     * 方案条目为纯字符串结构（title/content/source），无日期/多态类型，故自建 ObjectMapper： 既避免注入外部可变对象（SpotBugs
     * EI_EXPOSE_REP2），也不依赖容器序列化配置。
     */
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 生成照护方案（幂等：同一报告已有活跃方案则直接返回）。
     *
     * @param orderId 评估单 id（须已生成报告）
     * @param operatorId 建议人（评估员）
     * @return 照护方案视图
     */
    @Transactional(rollbackFor = Exception.class)
    public CarePlanResponse generate(Long orderId, Long operatorId) {
        if (operatorId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "operatorId 必填（建议人留痕）");
        }
        EvalReport report = reportService.findReport(orderId);
        if (report == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "报告尚未生成，无法生成照护方案: orderId=" + orderId);
        }
        CarePlan existing =
                planMapper.selectOne(new QueryWrapper<CarePlan>().eq("report_id", report.getId()));
        if (existing != null) {
            return toResponse(existing);
        }
        CarePlan plan = new CarePlan();
        plan.setReportId(report.getId());
        plan.setElderId(report.getElderId());
        plan.setGradeCode(report.getGradeCode());
        plan.setItemsJson(writeItems(buildItems(report.getGradeCode())));
        plan.setSuggestBy(operatorId);
        plan.setStatus(PLAN_PENDING);
        plan.setDeleted(0);
        planMapper.insert(plan);
        return toResponse(plan);
    }

    /**
     * 管理员确认照护方案（PRD：评估员建议、管理员确认）。
     *
     * @param orderId 评估单 id
     * @param operatorId 确认人（必须 ≠ 建议人）
     * @return 照护方案视图
     */
    @Transactional(rollbackFor = Exception.class)
    public CarePlanResponse confirm(Long orderId, Long operatorId) {
        if (operatorId == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "operatorId 必填（确认人留痕）");
        }
        CarePlan plan = requirePlan(orderId);
        if (plan.getSuggestBy() != null && plan.getSuggestBy().equals(operatorId)) {
            throw new BizException(ErrorCode.ROLE_NOT_PERMITTED, "建议人与确认人必须不同（评估员建议、管理员确认）");
        }
        if (plan.getStatus() != null && plan.getStatus() == PLAN_CONFIRMED) {
            return toResponse(plan);
        }
        plan.setStatus(PLAN_CONFIRMED);
        plan.setConfirmedBy(operatorId);
        plan.setConfirmedAt(LocalDateTime.now());
        planMapper.updateById(plan);
        return toResponse(plan);
    }

    /** 查询评估单的照护方案；未生成时抛 NOT_FOUND。 */
    public CarePlanResponse get(Long orderId) {
        return toResponse(requirePlan(orderId));
    }

    private CarePlan requirePlan(Long orderId) {
        EvalReport report = reportService.findReport(orderId);
        if (report == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "报告尚未生成: orderId=" + orderId);
        }
        CarePlan plan =
                planMapper.selectOne(new QueryWrapper<CarePlan>().eq("report_id", report.getId()));
        if (plan == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "照护方案尚未生成: orderId=" + orderId);
        }
        return plan;
    }

    /** 模板命中即按等级给建议；未命中降级通用建议（PRD P02）。 */
    private List<CarePlanItem> buildItems(String gradeCode) {
        List<CarePlanItem> items = new ArrayList<>();
        if (gradeCode != null) {
            List<CareTemplate> templates =
                    templateMapper.selectList(
                            new QueryWrapper<CareTemplate>()
                                    .eq("grade_code", gradeCode)
                                    .eq("status", 1)
                                    .orderByAsc("sort_no", "id"));
            for (CareTemplate t : templates) {
                items.add(new CarePlanItem(t.getTitle(), t.getContent(), SOURCE_TEMPLATE));
            }
        }
        if (items.isEmpty()) {
            items.addAll(generalItems());
        }
        return items;
    }

    private List<CarePlanItem> generalItems() {
        List<CarePlanItem> items = new ArrayList<>();
        items.add(new CarePlanItem("生活照料", "保持规律作息与均衡膳食，每日协助完成进食、穿衣、洗漱等基本生活活动。", SOURCE_GENERAL));
        items.add(new CarePlanItem("防跌倒", "清除室内绊脚物、卫生间加装扶手与防滑垫，夜间保留照明，起身遵循「三部曲」。", SOURCE_GENERAL));
        items.add(new CarePlanItem("健康随访", "按医嘱规律用药并设置提醒，每周记录血压/血糖等指标，异常及时就医。", SOURCE_GENERAL));
        return items;
    }

    private String writeItems(List<CarePlanItem> items) {
        try {
            return objectMapper.writeValueAsString(items);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "照护方案序列化失败");
        }
    }

    private CarePlanResponse toResponse(CarePlan plan) {
        CarePlanResponse resp = new CarePlanResponse();
        resp.setPlanId(plan.getId());
        resp.setReportId(plan.getReportId());
        resp.setElderId(plan.getElderId());
        resp.setGradeCode(plan.getGradeCode());
        resp.setStatus(plan.getStatus());
        resp.setSuggestBy(plan.getSuggestBy());
        resp.setConfirmedBy(plan.getConfirmedBy());
        resp.setConfirmedAt(plan.getConfirmedAt());
        resp.setCreatedAt(plan.getCreatedAt());
        resp.setItems(readItems(plan.getItemsJson()));
        return resp;
    }

    private List<CarePlanItem> readItems(String json) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<CarePlanItem>>() {});
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.SYSTEM_ERROR, "照护方案解析失败");
        }
    }
}
