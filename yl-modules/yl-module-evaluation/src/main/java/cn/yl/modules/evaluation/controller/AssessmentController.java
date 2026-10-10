package cn.yl.modules.evaluation.controller;

import cn.yl.api.security.Logical;
import cn.yl.api.security.LoginUser;
import cn.yl.api.security.PermissionCode;
import cn.yl.api.security.RequiresPermission;
import cn.yl.api.security.SecurityUtils;
import cn.yl.common.api.R;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.evaluation.dto.AssessmentResultResponse;
import cn.yl.modules.evaluation.dto.CarePlanResponse;
import cn.yl.modules.evaluation.dto.CreateAssessmentRequest;
import cn.yl.modules.evaluation.dto.EvalReportResponse;
import cn.yl.modules.evaluation.dto.ReportPdfResponse;
import cn.yl.modules.evaluation.dto.ReviewActionRequest;
import cn.yl.modules.evaluation.dto.ReviewLogResponse;
import cn.yl.modules.evaluation.dto.SubmitAnswersRequest;
import cn.yl.modules.evaluation.dto.SubmitAssessmentRequest;
import cn.yl.modules.evaluation.service.AssessmentReportService;
import cn.yl.modules.evaluation.service.AssessmentReviewService;
import cn.yl.modules.evaluation.service.AssessmentService;
import cn.yl.modules.evaluation.service.CarePlanService;
import cn.yl.modules.evaluation.service.ReportPdfService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.validation.Valid;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评估中心接口（C1）：评估任务/评估单创建、26 项作答、提交并判定、复核状态机、报告与照护方案。
 *
 * <p>提交链路：{@code POST /assessments} → {@code PUT /assessments/{id}/answers} → {@code POST
 * /assessments/{id}/submit}。 最后一步调用 GB/T 42195-2022 分级引擎并落库等级结果。
 *
 * <p>复核链路（PRD §4.4）：{@code POST /assessments/{id}/review/publish ｜ reject ｜ void}， 录入与复核人互斥（登录用户 ID
 * ≠ {@code eval_order.assessor_id}）。
 *
 * <p><b>鉴权口径（CR-M2-002）</b>：
 *
 * <ul>
 *   <li><b>第二闸（授权）</b>：每个端点标注 {@link RequiresPermission}，权限码与 {@code sys_permission} 种子逐格对齐； {@code
 *       evaluation:order:void} 为敏感点，经第四闸要求二次验证一次性凭据（{@code X-Second-Verify-Token}）。
 *   <li><b>操作者来源</b>：一律 {@code SecurityUtils.requireUserId()} 取登录态；<b>请求体不再承载身份</b> （{@code
 *       ReviewActionRequest.operatorId} 已移除），故"传他人 ID 即冒用操作者"无法复现。
 *   <li><b>第三闸（数据域）</b>：可见范围由 {@code DataScopePermissionHandler} 按登录态 dataScope
 *       行级收口（评估员/机构管理员=本机构）。
 *   <li>读类端点采用「权限码从宽 + 数据域从严」口径（与权限矩阵「可见范围不靠权限点区分」的既定设计一致）：如 {@code get} 允许 {@code
 *       evaluation:task:read} 或 {@code report:view}，但老人/家属经第三闸仍只能看到本人数据。
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/evaluation/assessments")
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification =
                                "AssessmentService 是 Spring 容器管理的无状态单例 Bean，构造器注入是标准 DI 用法"))
public class AssessmentController {

    private final AssessmentService assessmentService;
    private final AssessmentReportService reportService;
    private final AssessmentReviewService reviewService;
    private final CarePlanService carePlanService;
    private final ReportPdfService pdfService;

    /**
     * 新建评估（任务 + 评估单 DRAFT）。
     *
     * <p>归属机构与录入人不再无条件采信请求体（同一类「身份来自请求体」缺陷）：
     *
     * <ul>
     *   <li><b>归属机构</b>：缺省取登录态的默认机构；显式传入时必须在本人机构域 {@code orgIds} 内，否则拒绝——机构域外的行 在第三闸（{@code org_id
     *       IN (orgIds)}）下对创建者自己也永久不可见，属于静默数据污染；
     *   <li><b>录入人</b>：允许为空（PRD「待接单」），非派单角色不得指派他人——否则可由「录入人填别人」绕过 PRD §2.2 的录入/复核互斥，使实际录入人仍能影响复核结论。
     * </ul>
     */
    @PostMapping
    @RequiresPermission(PermissionCode.EVAL_ORDER_CREATE)
    public R<AssessmentResultResponse> create(@Valid @RequestBody CreateAssessmentRequest request) {
        LoginUser user = SecurityUtils.requireUser();
        if (request.getOrgId() == null) {
            request.setOrgId(user.requireOrgId());
        } else if (!user.orgIds().contains(request.getOrgId())) {
            throw new BizException(
                    ErrorCode.ROLE_NOT_PERMITTED, "归属机构超出本人数据域：orgId=" + request.getOrgId());
        }
        if (request.getAssessorId() != null
                && !user.hasPermission(PermissionCode.EVAL_ORDER_REVIEW)
                && request.getAssessorId() != user.userId()) {
            throw new BizException(
                    ErrorCode.ROLE_NOT_PERMITTED,
                    "无派单权限，录入人只能指派本人（或留空待接单）：assessorId=" + request.getAssessorId());
        }
        return R.ok(assessmentService.create(request));
    }

    /** 保存作答（逐项 upsert，可重复保存）。 */
    @PutMapping("/{orderId}/answers")
    @RequiresPermission(PermissionCode.EVAL_ITEM_INPUT)
    public R<Integer> saveAnswers(
            @PathVariable Long orderId, @Valid @RequestBody SubmitAnswersRequest request) {
        return R.ok(assessmentService.saveAnswers(orderId, request));
    }

    /** 提交评估：校验必答 → 调分级引擎 → 落等级结果。 */
    @PostMapping("/{orderId}/submit")
    @RequiresPermission(PermissionCode.EVAL_ITEM_INPUT)
    public R<AssessmentResultResponse> submit(
            @PathVariable Long orderId, @Valid @RequestBody SubmitAssessmentRequest request) {
        return R.ok(assessmentService.submit(orderId, request));
    }

    /** 查询评估单当前状态。 */
    @GetMapping("/{orderId}")
    @RequiresPermission(
            value = {PermissionCode.EVAL_TASK_READ, PermissionCode.REPORT_VIEW},
            logical = Logical.OR)
    public R<AssessmentResultResponse> get(@PathVariable Long orderId) {
        return R.ok(assessmentService.get(orderId));
    }

    /** 生成报告（幂等）：绑定初判/终判/变化原因 + 规则版本与指纹，支持事后复算（PRD §1.4）。 */
    @PostMapping("/{orderId}/report")
    @RequiresPermission(PermissionCode.EVAL_ITEM_INPUT)
    public R<EvalReportResponse> bindReport(@PathVariable Long orderId) {
        return R.ok(reportService.bindReport(orderId));
    }

    /** 查询评估报告（含维度得分、PDF 键与验真二维码）。 */
    @GetMapping("/{orderId}/report")
    @RequiresPermission(PermissionCode.REPORT_VIEW)
    public R<EvalReportResponse> getReport(@PathVariable Long orderId) {
        return R.ok(reportService.getReport(orderId));
    }

    // ---------------- 复核状态机（PRD §4.4） ----------------

    /**
     * 复核通过并发布：SUBMITTED → PUBLISHED，报告转有效。
     *
     * <p>复核人取登录态（非请求体）；权限码 {@code evaluation:order:review}（当前仅 ORG_ADMIN 持有）。
     */
    @PostMapping("/{orderId}/review/publish")
    @RequiresPermission(PermissionCode.EVAL_ORDER_REVIEW)
    public R<AssessmentResultResponse> publish(
            @PathVariable Long orderId, @Valid @RequestBody ReviewActionRequest request) {
        return R.ok(
                reviewService.publish(
                        orderId, SecurityUtils.requireUserId(), request.getOpinion()));
    }

    /** 复核驳回：SUBMITTED → REJECTED，退回录入人可修改重提（意见必填）。 */
    @PostMapping("/{orderId}/review/reject")
    @RequiresPermission(PermissionCode.EVAL_ORDER_REVIEW)
    public R<AssessmentResultResponse> reject(
            @PathVariable Long orderId, @Valid @RequestBody ReviewActionRequest request) {
        return R.ok(
                reviewService.reject(orderId, SecurityUtils.requireUserId(), request.getOpinion()));
    }

    /**
     * 作废：SUBMITTED / PUBLISHED / REJECTED → VOID，报告转已作废。
     *
     * <p>{@code evaluation:order:void} 为敏感权限点（{@code need_second_verify=1}），按 {@code
     * docs/design/B2-account-rbac-design.md} §2.1 的裁决「默认<b>不常驻授权</b>，由机构管理员运行时经二次验证临时提权」，
     * 种子文件中**不授予任何角色**。若此处按「必须持有该码」校验，作废将变成谁都无法调用的死路——即卡片所警示的 「全量 403 假性收紧」。
     *
     * <p>故采用 <b>OR 语义</b>：{@code evaluation:order:review}（机构管理员常驻持有）<b>或</b> {@code
     * evaluation:order:void}（运行时提权后持有）。关键点在于——只要所需码表**含**敏感码 {@code order:void}， {@code
     * PermissionAspect} 仍会联动第四闸，强制校验二次验证一次性凭据（{@code X-Second-Verify-Token}，由 {@code POST
     * /api/v1/auth/second-verify} 换取）。因此最终效力与设计裁决一致：<b>机构管理员 + 二次验证</b>方可作废。
     */
    @PostMapping("/{orderId}/review/void")
    @RequiresPermission(
            value = {PermissionCode.EVAL_ORDER_REVIEW, PermissionCode.EVAL_ORDER_VOID},
            logical = Logical.OR)
    public R<AssessmentResultResponse> voidOrder(
            @PathVariable Long orderId, @Valid @RequestBody ReviewActionRequest request) {
        return R.ok(
                reviewService.voidOrder(
                        orderId, SecurityUtils.requireUserId(), request.getOpinion()));
    }

    /**
     * 流转日志（审计链）。
     *
     * <p>可读范围取「复核读 + 复核 + 任务读」三者任一：监管（{@code evaluation:order:review:read}）、机构管理员（{@code
     * evaluation:order:review}）、录入评估员（{@code evaluation:task:read}，需看到驳回意见才能据以修改重提，PRD §4.3）。
     */
    @GetMapping("/{orderId}/review-logs")
    @RequiresPermission(
            value = {
                PermissionCode.EVAL_ORDER_REVIEW_READ,
                PermissionCode.EVAL_ORDER_REVIEW,
                PermissionCode.EVAL_TASK_READ
            },
            logical = Logical.OR)
    public R<List<ReviewLogResponse>> reviewLogs(@PathVariable Long orderId) {
        return R.ok(reviewService.logs(orderId));
    }

    // ---------------- 照护建议（PRD P02） ----------------

    /**
     * 生成照护方案（按等级匹配模板；无匹配降级通用建议）。
     *
     * <p>请求体可省略（旧客户端携带的 {@code operatorId} 被忽略）；建议人取登录态。评估员持 {@code
     * care:plan:edit:suggest}（受限态）、管理员持 {@code care:plan:edit}，二者任一可生成。
     */
    @PostMapping("/{orderId}/care-plan")
    @RequiresPermission(
            value = {PermissionCode.CARE_PLAN_EDIT, PermissionCode.CARE_PLAN_EDIT_SUGGEST},
            logical = Logical.OR)
    public R<CarePlanResponse> generateCarePlan(
            @PathVariable Long orderId,
            @RequestBody(required = false) ReviewActionRequest request) {
        return R.ok(carePlanService.generate(orderId, SecurityUtils.requireUserId()));
    }

    /** 管理员确认照护方案（确认人取登录态，须 ≠ 建议人）。 */
    @PostMapping("/{orderId}/care-plan/confirm")
    @RequiresPermission(PermissionCode.CARE_PLAN_EDIT)
    public R<CarePlanResponse> confirmCarePlan(
            @PathVariable Long orderId,
            @RequestBody(required = false) ReviewActionRequest request) {
        return R.ok(carePlanService.confirm(orderId, SecurityUtils.requireUserId()));
    }

    /** 查询照护方案。 */
    @GetMapping("/{orderId}/care-plan")
    @RequiresPermission(
            value = {
                PermissionCode.REPORT_VIEW,
                PermissionCode.CARE_PLAN_EDIT,
                PermissionCode.CARE_PLAN_EDIT_SUGGEST
            },
            logical = Logical.OR)
    public R<CarePlanResponse> getCarePlan(@PathVariable Long orderId) {
        return R.ok(carePlanService.get(orderId));
    }

    // ---------------- 报告 PDF（PRD P02 下载 PDF） ----------------

    /** 生成报告 PDF 并回填对象键与验真二维码内容。 */
    @PostMapping("/{orderId}/report/pdf")
    @RequiresPermission(PermissionCode.REPORT_VIEW)
    public R<ReportPdfResponse> generateReportPdf(@PathVariable Long orderId) {
        return R.ok(pdfService.generate(orderId));
    }
}
