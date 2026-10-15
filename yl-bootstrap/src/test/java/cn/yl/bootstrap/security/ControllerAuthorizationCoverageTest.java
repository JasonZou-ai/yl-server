package cn.yl.bootstrap.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yl.api.health.HealthController;
import cn.yl.api.security.RequiresPermission;
import cn.yl.modules.account.controller.AuthController;
import cn.yl.modules.account.controller.OrgController;
import cn.yl.modules.account.controller.SmsController;
import cn.yl.modules.archive.controller.DataRevealController;
import cn.yl.modules.archive.controller.ElderController;
import cn.yl.modules.archive.controller.FamilyBindingController;
import cn.yl.modules.evaluation.controller.AssessmentController;
import cn.yl.modules.evaluation.controller.EvalTaskController;
import cn.yl.modules.evaluation.controller.EvaluationItemController;
import cn.yl.modules.evaluation.controller.GradingController;
import cn.yl.modules.evaluation.controller.ReportController;
import cn.yl.modules.evaluation.controller.ReportListController;
import cn.yl.modules.evaluation.controller.ReportVerifyController;
import cn.yl.modules.evaluation.controller.RuleMappingController;
import cn.yl.modules.report.controller.SuperviseExportController;
import cn.yl.modules.supervise.controller.SuperviseReportController;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 控制器鉴权覆盖测试（四道闸·第二闸）—— <b>CR-M2-006 的结构性防回归</b>。
 *
 * <p>CR-M2-006 的根因是「<b>注解漏写 + 文件读取绕过数据域</b>」两端同时失守：报告 PDF 下载端点既无 {@link RequiresPermission}，其服务又零
 * SQL 查询使第三闸失效。前者属"漏写注解"这一类<b>纯人工疏漏</b>，Mock 单测与业务冒烟都难以覆盖 （冒烟只测已接入的链路），故用反射把口径机械化：<b>凡是 HTTP
 * 处理端点，必须显式声明权限码，或在白名单里给出理由</b>。
 *
 * <p>白名单只有三类，且必须经得起追问：
 *
 * <ol>
 *   <li><b>会话入口/出口</b>（{@code /auth/login}、{@code /auth/refresh}、{@code /auth/second-verify}、{@code
 *       /auth/logout}）——入口调用时尚无既有登录态可用、出口（logout）的安全边界是登录态本身， 二者均不依赖权限矩阵某一格。入口安全边界是渠道凭据校验本身（见
 *       {@code CR-M2-004} 的渠道降级开关）；logout 为 D-4 新增（契约 {@code x-required-permission:
 *       none}，会话出口不应因"无某权限码"而无法登出）；
 *   <li><b>健康探针</b>（{@code /system/health}）——运维探针，不承载任何业务数据；
 *   <li><b>公开验真</b>（{@code /reports/verify}）——扫码即验、<b>无登录态</b>（契约 {@code security: []} + {@code
 *       x-data-scope.mode: public}）。其安全边界是「最小返回面」：只返回 {@code {valid, gradeCode,
 *       publishedAt}}，<b>绝不返回</b>姓名/身份证/手机号；批量枚举风险由既有 {@code RateLimitInterceptor} 兜底。
 * </ol>
 *
 * <p>与之配套：{@link #whitelistHasNoRedundantEntry()} 断言白名单不残留 —— 注解补齐后若忘记摘白名单，本测试同样变红 （口径同 SpotBugs 的
 * {@code US_USELESS_SUPPRESSION}：本项目不接受"多余的豁免"）。
 */
class ControllerAuthorizationCoverageTest {

    /** 纳入覆盖检查的控制器。<b>新增控制器必须显式登记</b>，否则本测试无法替它兜底。 */
    private static final List<Class<?>> CONTROLLERS =
            List.of(
                    AuthController.class,
                    AssessmentController.class,
                    EvaluationItemController.class,
                    GradingController.class,
                    ReportController.class,
                    HealthController.class,
                    ElderController.class,
                    DataRevealController.class,
                    // D-3 报表与监管域（7 端点跨 4 模块）：新增 6 个控制器
                    EvalTaskController.class,
                    RuleMappingController.class,
                    ReportVerifyController.class,
                    OrgController.class,
                    SuperviseExportController.class,
                    SuperviseReportController.class,
                    // D-4 家属域（9 端点）：新增 1 个控制器
                    FamilyBindingController.class,
                    // P2-8-A 短信核身通道（3 端点，未登录前置）
                    SmsController.class,
                    // rR1a6L B5-后端：本人报告列表（1 端点，须挂 @RequiresPermission）
                    ReportListController.class);

    /** 允许无第二闸的端点（显式豁免，见类注释的四类理由）。 */
    private static final Set<String> NO_PERMISSION_WHITELIST =
            Set.of(
                    "AuthController#login",
                    "AuthController#refresh",
                    "AuthController#secondVerify",
                    "AuthController#logout",
                    // P2-8-A 短信核身：未登录前置端点，安全边界为通道自身（频控/图形验证/一次性核销）
                    "AuthController#loginByTicket",
                    "SmsController#captchaImage",
                    "SmsController#send",
                    "SmsController#verify",
                    "HealthController#health",
                    "ReportVerifyController#verify");

    @Test
    void everyHandlerEndpointDeclaresPermission() {
        List<String> naked = new ArrayList<>();
        int endpoints = 0;
        for (String key : handlerKeys()) {
            endpoints++;
            if (!NO_PERMISSION_WHITELIST.contains(key) && !declaresPermission(key)) {
                naked.add(key);
            }
        }
        assertTrue(endpoints >= 26, "登记的控制器至少应覆盖 26 个端点（当前 " + endpoints + "），数量骤降说明登记表过期");
        assertEquals(
                List.of(),
                naked,
                "存在未声明权限码的端点（裸奔）：" + naked + " —— 请在方法上补 @RequiresPermission，确需放行的必须加入白名单并说明理由");
    }

    /**
     * 白名单双向自检：① 每个条目都真实存在；② 每个条目当前<b>确实</b>没有声明权限码。
     *
     * <p>②是"多余豁免"的拦截 —— 端点补上注解后白名单未摘除，会让本测试继续"假装那里还需要豁免"。
     */
    @Test
    void whitelistHasNoRedundantEntry() {
        Set<String> keys = handlerKeys();
        List<String> stale = new ArrayList<>();
        for (String entry : NO_PERMISSION_WHITELIST) {
            if (!keys.contains(entry) || declaresPermission(entry)) {
                stale.add(entry);
            }
        }
        assertEquals(List.of(), stale, "白名单存在无效/冗余条目（端点已不存在，或已自行声明权限码）：" + stale);
    }

    private boolean declaresPermission(String handlerKey) {
        String[] parts = handlerKey.split("#", 2);
        for (Class<?> controller : CONTROLLERS) {
            if (!controller.getSimpleName().equals(parts[0])) {
                continue;
            }
            if (controller.isAnnotationPresent(RequiresPermission.class)) {
                return true;
            }
            for (Method method : controller.getDeclaredMethods()) {
                if (method.getName().equals(parts[1])) {
                    return method.isAnnotationPresent(RequiresPermission.class);
                }
            }
        }
        return false;
    }

    /** 全部 HTTP 处理端点（{@code ClassSimpleName#methodName}），按登记顺序去重。 */
    private Set<String> handlerKeys() {
        Set<String> keys = new LinkedHashSet<>();
        for (Class<?> controller : CONTROLLERS) {
            for (Method method : controller.getDeclaredMethods()) {
                if (isHandler(method)) {
                    keys.add(controller.getSimpleName() + "#" + method.getName());
                }
            }
        }
        return keys;
    }

    /** 是否 Spring MVC 处理端点：带 {@code org.springframework.web.bind.annotation.*Mapping} 注解。 */
    private boolean isHandler(Method method) {
        for (Annotation annotation : method.getAnnotations()) {
            String name = annotation.annotationType().getName();
            if (name.startsWith("org.springframework.web.bind.annotation")
                    && name.endsWith("Mapping")) {
                return true;
            }
        }
        return false;
    }
}
