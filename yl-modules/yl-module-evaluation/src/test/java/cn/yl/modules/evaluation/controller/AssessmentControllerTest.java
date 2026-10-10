package cn.yl.modules.evaluation.controller;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import cn.yl.api.security.LoginUser;
import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.modules.evaluation.dto.CreateAssessmentRequest;
import cn.yl.modules.evaluation.dto.ReviewActionRequest;
import cn.yl.modules.evaluation.service.AssessmentReportService;
import cn.yl.modules.evaluation.service.AssessmentReviewService;
import cn.yl.modules.evaluation.service.AssessmentService;
import cn.yl.modules.evaluation.service.CarePlanService;
import cn.yl.modules.evaluation.service.ReportPdfService;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 评估控制器操作者来源测试（CR-M2-002 越权缺口回归）。
 *
 * <p>缺口现场：复核/发布/作废/照护确认的操作者取自<b>请求体</b> {@code operatorId}，实测"以评估员令牌调用 {@code review/publish}
 * 并在请求体传机构管理员 ID"即可成功（{@code eval_order.reviewer_id} 被记为机构管理员）。收口口径：操作者一律取登录态，请求体不再承载身份。
 *
 * <p>本测试固定两件事：① 服务收到的 operatorId **等于登录用户**，与请求体内容无关；② 旧客户端携带的 {@code operatorId} 字段因
 * Jackson「忽略未知字段」被静默丢弃，<b>不会 400</b>，故四端无需同批次发版。
 */
@ExtendWith(MockitoExtension.class)
class AssessmentControllerTest {

    private static final long ADMIN_ID = 900000000000000002L;
    private static final long ASSESSOR_ID = 900000000000000001L;

    @Mock private AssessmentService assessmentService;
    @Mock private AssessmentReportService reportService;
    @Mock private AssessmentReviewService reviewService;
    @Mock private CarePlanService carePlanService;
    @Mock private ReportPdfService pdfService;

    private AssessmentController controller;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        controller =
                new AssessmentController(
                        assessmentService,
                        reportService,
                        reviewService,
                        carePlanService,
                        pdfService);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void publishUsesLoginUserAsOperator() {
        login(ADMIN_ID);

        controller.publish(7001L, request("同意发布"));

        verify(reviewService).publish(7001L, ADMIN_ID, "同意发布");
    }

    @Test
    void rejectUsesLoginUserAsOperator() {
        login(ADMIN_ID);

        controller.reject(7002L, request("第 12 项留证缺失，请补充"));

        verify(reviewService).reject(7002L, ADMIN_ID, "第 12 项留证缺失，请补充");
    }

    @Test
    void voidOrderUsesLoginUserAsOperator() {
        login(ADMIN_ID);

        controller.voidOrder(7003L, request("评估对象信息有误，作废重评"));

        verify(reviewService).voidOrder(7003L, ADMIN_ID, "评估对象信息有误，作废重评");
    }

    @Test
    void confirmCarePlanUsesLoginUserAsOperator() {
        login(ADMIN_ID);

        controller.confirmCarePlan(7004L, request(null));

        verify(carePlanService).confirm(7004L, ADMIN_ID);
    }

    @Test
    void generateCarePlanUsesLoginUserAsOperator() {
        login(ASSESSOR_ID);

        controller.generateCarePlan(7004L, null);

        verify(carePlanService).generate(7004L, ASSESSOR_ID);
    }

    /** 无登录态（无令牌 / 令牌非法）时不得以匿名身份落到业务，必须明确拒绝。 */
    @Test
    void publishRejectedWhenNotAuthenticated() {
        BizException ex =
                assertThrows(BizException.class, () -> controller.publish(7001L, request("同意发布")));

        assertEquals(ErrorCode.UNAUTHORIZED.getCode(), ex.getCode());
    }

    /** 身份字段必须彻底移除：DTO 一旦能承载 operatorId，未来就可能被误当作身份来源。 */
    @Test
    void reviewActionRequestCarriesNoIdentityField() {
        assertTrue(
                Arrays.stream(ReviewActionRequest.class.getDeclaredFields())
                        .noneMatch(field -> "operatorId".equals(field.getName())),
                "ReviewActionRequest 不得再承载 operatorId（身份只来自登录态）");
    }

    /** 旧客户端仍携带 {@code operatorId}：契约兼容（静默忽略），不会 400。 */
    @Test
    void legacyOperatorIdFieldInBodyIsIgnored() {
        ObjectMapper mapper =
                new ObjectMapper().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

        ReviewActionRequest parsed =
                assertDoesNotThrow(
                        () ->
                                mapper.readValue(
                                        "{\"operatorId\":900000000000000001,\"opinion\":\"同意发布\"}",
                                        ReviewActionRequest.class));

        assertEquals("同意发布", parsed.getOpinion(), "未知字段 operatorId 应被忽略而非报错");
    }

    private ReviewActionRequest request(String opinion) {
        ReviewActionRequest req = new ReviewActionRequest();
        req.setOpinion(opinion);
        return req;
    }

    // ---------------- 新建评估：归属机构与录入人也不得无条件采信请求体 ----------------

    /** 请求体未给机构时，归属机构取登录态默认机构（避免落到本人机构域之外而永久不可见）。 */
    @Test
    void createDefaultsOrgToLoginUserOrg() {
        login(ADMIN_ID);
        CreateAssessmentRequest req = new CreateAssessmentRequest();
        req.setElderId(810000000000000001L);

        controller.create(req);

        assertEquals(1L, req.getOrgId(), "缺省机构应取登录态默认机构");
        verify(assessmentService).create(req);
    }

    /** 显式指定的机构越出本人 dataScope 时必须拒绝（否则该行在第三闸下连创建者自己都查不到）。 */
    @Test
    void createRejectsOrgOutsideDataScope() {
        login(ADMIN_ID);
        CreateAssessmentRequest req = new CreateAssessmentRequest();
        req.setElderId(810000000000000001L);
        req.setOrgId(99L);

        BizException ex = assertThrows(BizException.class, () -> controller.create(req));

        assertEquals(ErrorCode.ROLE_NOT_PERMITTED.getCode(), ex.getCode());
    }

    /** 评估员（无派单权限）不得指派他人为录入人——否则可绕过录入/复核互斥。 */
    @Test
    void createRejectsAssigningOthersWithoutDispatchPermission() {
        loginWithPerms(ASSESSOR_ID, List.of("evaluation:order:create", "evaluation:item:input"));
        CreateAssessmentRequest req = new CreateAssessmentRequest();
        req.setElderId(810000000000000001L);
        req.setOrgId(1L);
        req.setAssessorId(ADMIN_ID);

        BizException ex = assertThrows(BizException.class, () -> controller.create(req));

        assertEquals(ErrorCode.ROLE_NOT_PERMITTED.getCode(), ex.getCode());
    }

    /** 评估员指派本人（含留空待接单）应放行。 */
    @Test
    void createAllowsSelfAssignmentForAssessor() {
        loginWithPerms(ASSESSOR_ID, List.of("evaluation:order:create", "evaluation:item:input"));
        CreateAssessmentRequest req = new CreateAssessmentRequest();
        req.setElderId(810000000000000001L);
        req.setOrgId(1L);
        req.setAssessorId(ASSESSOR_ID);

        controller.create(req);

        verify(assessmentService).create(req);
    }

    private void login(long userId) {
        loginWithPerms(userId, List.of("evaluation:order:review", "evaluation:order:void"));
    }

    private void loginWithPerms(long userId, List<String> perms) {
        LoginUser user = new LoginUser(userId, 1L, List.of("ASSESSOR"), perms, List.of(1L), 2);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(user, null, List.of()));
    }
}
