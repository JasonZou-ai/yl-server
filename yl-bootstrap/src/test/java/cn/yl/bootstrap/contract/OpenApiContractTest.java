package cn.yl.bootstrap.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.yl.api.health.HealthController;
import cn.yl.api.security.PermissionCode;
import cn.yl.modules.account.controller.AuthController;
import cn.yl.modules.account.controller.OrgContactController;
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
import java.io.BufferedReader;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 契约门禁：`openapi/yl-api.yaml` 的结构自洽性 + <b>与实现的路径漂移</b>。
 *
 * <p>背景（D-1 契约盘点）：契约 22 个操作里只有 3 个与实现对齐 —— 「契约 = 实现镜像」这一属性<b>事实上已失效</b>，
 * 而失效过程无人察觉（没有任何门禁盯着它）。本测试把该属性重新变成机器可校验的断言：
 *
 * <ol>
 *   <li><b>漂移（双向）</b>：标注 `x-status: implemented` 的契约操作集合，必须与控制器端点集合<b>完全相等</b> —— 多一个（契约写了没实现却标
 *       implemented）或少一个（实现了没登记）都失败；
 *   <li><b>扩展键齐备</b>：每个操作必须声明 `x-required-permission` 与 `x-data-scope.mode`；
 *   <li><b>mode 与权限码自洽</b>：`mode: public` ⟺ 权限码为 `none`（反向则意味着"公开端点却要权限"或"受限端点却无权限"）；
 *   <li><b>权限码存在于常量表</b>：契约不得造出 `PermissionCode` 里没有的码（连库版本见 `scripts/verify-schema.sh`）；
 *   <li><b>敏感点标注</b>：4 个敏感权限点必须带 `x-second-verify: true`。
 * </ol>
 *
 * <p>无 Maven 环境下的等价快速自检见 {@code scripts/verify-openapi.sh}（同源口径、互为冗余）。
 */
class OpenApiContractTest {

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
                    // CR-M2-009 B-1-a：所属机构公开联系方式（契约 49→50）
                    OrgContactController.class,
                    // P2-8-A 短信核身通道（3 端点，未登录前置）
                    SmsController.class,
                    // rR1a6L B5-后端：本人报告列表（1 端点）——新增控制器必须显式登记，否则双向漂移断言红
                    ReportListController.class);

    /** 5 个 HTTP 动词在 OpenAPI 里的键名。 */
    private static final List<String> METHODS = List.of("get", "post", "put", "delete", "patch");

    /** 4 个敏感权限点（`sys_permission.need_second_verify = 1`）必须强制第四闸。 */
    private static final Set<String> SENSITIVE_CODES =
            Set.of(
                    PermissionCode.DATA_EXPORT,
                    PermissionCode.EVAL_ORDER_VOID,
                    PermissionCode.ACCOUNT_FAMILY_UNBIND,
                    PermissionCode.DATA_REVEAL);

    private static final Set<String> DATA_SCOPE_MODES =
            Set.of("public", "self", "org", "relation", "all");

    private static Map<String, Object> spec;

    @BeforeAll
    static void loadSpec() throws IOException {
        Path file = locateSpec();
        try (BufferedReader reader = Files.newBufferedReader(file)) {
            spec = new Yaml().load(reader);
        }
        assertNotNull(spec, "契约文件解析结果为空: " + file);
    }

    @Test
    void declaredImplementedOperationsMatchControllersExactly() {
        Set<String> declared = new TreeSet<>();
        for (Map.Entry<String, Map<String, Object>> op : operations().entrySet()) {
            if ("implemented".equals(op.getValue().get("x-status"))) {
                declared.add(op.getKey());
            }
        }
        Set<String> actual = controllerEndpoints();
        assertEquals(
                actual,
                declared,
                "契约 `x-status: implemented` 与控制器端点不一致（左=代码，右=契约）。"
                        + "契约缺："
                        + diff(actual, declared)
                        + "；契约多："
                        + diff(declared, actual));
    }

    @Test
    void everyOperationDeclaresPermissionAndDataScope() {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> op : operations().entrySet()) {
            Map<String, Object> node = op.getValue();
            Object permission = node.get("x-required-permission");
            if (permission == null || String.valueOf(permission).isBlank()) {
                missing.add(op.getKey() + " 缺 x-required-permission");
            }
            Object scope = node.get("x-data-scope");
            if (!(scope instanceof Map<?, ?> scopeMap) || scopeMap.get("mode") == null) {
                missing.add(op.getKey() + " 缺 x-data-scope.mode");
            }
        }
        assertEquals(List.of(), missing, "契约存在未声明鉴权口径的操作：\n" + String.join("\n", missing));
    }

    /**
     * {@code mode: public} 必须搭配 `none`（公开端点不得要求权限码）。
     *
     * <p>反向不成立：既需登录、又不校验权限码的会话相关端点（{@code /auth/second-verify}、{@code /auth/logout}）合法地 取 {@code
     * none} —— 它们的安全边界是渠道凭据与登录态本身，而非权限矩阵的某一格。
     */
    @Test
    void publicModeImpliesNonePermission() {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> op : operations().entrySet()) {
            Map<String, Object> node = op.getValue();
            String mode = String.valueOf(scopeMode(node));
            String permission = String.valueOf(node.get("x-required-permission"));
            if ("public".equals(mode) && !"none".equals(permission)) {
                violations.add(op.getKey() + "：mode=public 却要求权限码 " + permission);
            }
        }
        assertEquals(List.of(), violations, "公开端点不得声明权限码，违例：\n" + String.join("\n", violations));
    }

    @Test
    void everyDeclaredPermissionCodeExistsInConstants() throws Exception {
        Set<String> known = permissionConstants();
        Set<String> unknown = new TreeSet<>();
        for (Map.Entry<String, Map<String, Object>> op : operations().entrySet()) {
            for (String code : permissionCodes(op.getValue())) {
                if (!known.contains(code)) {
                    unknown.add(code + "（" + op.getKey() + "）");
                }
            }
        }
        assertEquals(
                List.of(),
                new ArrayList<>(unknown),
                "契约声明了 PermissionCode 中不存在的权限码（禁止私造）：" + unknown);
    }

    @Test
    void sensitiveCodesRequireSecondVerify() {
        List<String> violations = new ArrayList<>();
        for (Map.Entry<String, Map<String, Object>> op : operations().entrySet()) {
            boolean sensitive =
                    permissionCodes(op.getValue()).stream().anyMatch(SENSITIVE_CODES::contains);
            boolean flagged = Boolean.TRUE.equals(op.getValue().get("x-second-verify"));
            if (sensitive && !flagged) {
                violations.add(op.getKey() + "：命中敏感权限点但未标注 x-second-verify");
            }
            if (!sensitive && flagged) {
                violations.add(op.getKey() + "：标注了 x-second-verify 但未命中任何敏感权限点");
            }
        }
        assertEquals(List.of(), violations, "第四闸口径与敏感点清单不一致：\n" + String.join("\n", violations));
    }

    @Test
    void dataScopeModesAreInEnum() {
        for (Map.Entry<String, Map<String, Object>> op : operations().entrySet()) {
            assertTrue(
                    DATA_SCOPE_MODES.contains(String.valueOf(scopeMode(op.getValue()))),
                    op.getKey() + " 的 x-data-scope.mode 取值非法：" + scopeMode(op.getValue()));
        }
        assertFalse(DATA_SCOPE_MODES.isEmpty());
    }

    // ---------------- 解析辅助 ----------------

    /** 契约操作集合：{@code GET /api/v1/xxx} → 该操作的 YAML 节点。 */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> operations() {
        Map<String, Map<String, Object>> ops = new java.util.LinkedHashMap<>();
        Object paths = spec.get("paths");
        assertTrue(paths instanceof Map<?, ?>, "契约缺少 paths 段");
        for (Map.Entry<?, ?> pathEntry : ((Map<?, ?>) paths).entrySet()) {
            if (!(pathEntry.getValue() instanceof Map<?, ?> pathNode)) {
                continue;
            }
            for (String verb : METHODS) {
                Object op = pathNode.get(verb);
                if (op instanceof Map<?, ?> opNode) {
                    ops.put(
                            verb.toUpperCase(Locale.ROOT) + " " + pathEntry.getKey(),
                            (Map<String, Object>) opNode);
                }
            }
        }
        return ops;
    }

    private Object scopeMode(Map<String, Object> op) {
        Object scope = op.get("x-data-scope");
        return scope instanceof Map<?, ?> map ? map.get("mode") : null;
    }

    private List<String> permissionCodes(Map<String, Object> op) {
        String raw = String.valueOf(op.get("x-required-permission"));
        List<String> codes = new ArrayList<>();
        for (String code : raw.split("\\|")) {
            String trimmed = code.trim();
            if (!trimmed.isEmpty() && !"none".equals(trimmed) && !"null".equals(trimmed)) {
                codes.add(trimmed);
            }
        }
        return codes;
    }

    /** 反射取 {@link PermissionCode} 全部常量值（契约不得造码）。 */
    private Set<String> permissionConstants() throws Exception {
        Set<String> codes = new LinkedHashSet<>();
        for (Field field : PermissionCode.class.getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    && field.getType() == String.class) {
                codes.add((String) field.get(null));
            }
        }
        assertTrue(codes.size() >= 22, "权限码常量表异常（应 ≥ 22，实际 " + codes.size() + "）");
        return codes;
    }

    /** 控制器端点集合：{@code GET /api/v1/xxx}（类级 @RequestMapping 前缀 + 方法级映射）。 */
    private Set<String> controllerEndpoints() {
        Set<String> endpoints = new TreeSet<>();
        for (Class<?> controller : CONTROLLERS) {
            String prefix = classPrefix(controller);
            for (Method method : controller.getDeclaredMethods()) {
                for (Annotation annotation : method.getAnnotations()) {
                    String name = annotation.annotationType().getName();
                    if (!name.startsWith("org.springframework.web.bind.annotation")
                            || !name.endsWith("Mapping")
                            || name.endsWith(".RequestMapping")) {
                        continue;
                    }
                    String verb =
                            name.substring(name.lastIndexOf('.') + 1)
                                    .replace("Mapping", "")
                                    .toUpperCase(Locale.ROOT);
                    String sub = firstValue(annotation);
                    endpoints.add(verb + " " + prefix + (sub == null ? "" : sub));
                }
            }
        }
        return endpoints;
    }

    private String classPrefix(Class<?> controller) {
        for (Annotation annotation : controller.getAnnotations()) {
            if (annotation
                    .annotationType()
                    .getName()
                    .equals("org.springframework.web.bind.annotation.RequestMapping")) {
                String value = firstValue(annotation);
                return value == null ? "" : value;
            }
        }
        return "";
    }

    /** 反射读取注解的 {@code value()}（各 Mapping 注解的 value 类型统一为 String[]）。 */
    private String firstValue(Annotation annotation) {
        try {
            Object value = annotation.annotationType().getMethod("value").invoke(annotation);
            if (value instanceof String[] array && array.length > 0 && !array[0].isBlank()) {
                return array[0];
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("读取注解 value 失败: " + annotation, e);
        }
        return null;
    }

    private List<String> diff(Set<String> left, Set<String> right) {
        List<String> only = new ArrayList<>(left);
        only.removeAll(right);
        return only;
    }

    /** 从当前工作目录向上查找契约文件（surefire 的工作目录是模块目录 yl-bootstrap）。 */
    private static Path locateSpec() {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++) {
            Path candidate = dir.resolve("openapi").resolve("yl-api.yaml");
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException(
                "未找到 openapi/yl-api.yaml（自 " + Paths.get("").toAbsolutePath() + " 向上 4 层）");
    }
}
