package cn.yl.modules.archive.mapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * ★F2 核验 Mapper 边界测试（D-4，QA 独立证伪项）—— <b>钉死"只返标量、不返任何老人字段/PII"</b>。
 *
 * <p>{@link FamilyBindingVerifyMapper} 是<b>本卡唯一绕过第三闸的读</b>（申请前目标老人尚未建立生效绑定 → scope=5 子查询恒空，
 * 无法经第三闸核验）。其唯一合法的暴露面是"标量"（计数 / 布尔）；一旦有人新增一个返回老人字段（如 {@code String name}、{@code Elder}、{@code
 * FamilyBindingView}）的方法，本测试立即变红。
 *
 * <p><b>★C5「豁免为接口级、全方法生效」</b>：因 {@code @InterceptorIgnore} 作用在<b>接口</b>上，对<b>全部</b>方法一次性生效 —— 故
 * {@code mapperOnlyExposesScalarReadMethods} 把<b>方法集合</b>钉死为 {@code {countExactMatch,
 * hasLoginAccount}}，防将来 有人往里加查询而<b>悄悄继承</b>第三闸豁免。
 */
class FamilyBindingVerifyMapperTest {

    /** 合法标量返回类型（设计 §1.1：Σ ⊆ {int, boolean, long}）。 */
    private static final Set<Class<?>> ALLOWED_SCALAR_TYPES =
            Set.of(int.class, boolean.class, long.class);

    /** 禁止出现的"老人字段/PII"返回类型（显式列举，防反射遗漏）。 */
    private static final Set<Class<?>> FORBIDDEN_TYPES =
            Set.of(String.class, byte[].class, List.class, java.util.Collection.class);

    @Test
    void allMethodsReturnScalarsOnly() {
        Method[] methods = FamilyBindingVerifyMapper.class.getDeclaredMethods();
        assertTrue(methods.length >= 2, "核验 Mapper 至少应有 countExactMatch + hasLoginAccount 两个方法");

        List<String> violations = new ArrayList<>();
        for (Method method : methods) {
            Class<?> returnType = method.getReturnType();
            if (!ALLOWED_SCALAR_TYPES.contains(returnType)) {
                violations.add(method.getName() + " → " + returnType.getTypeName());
            }
        }
        assertEquals(
                List.of(),
                violations,
                "FamilyBindingVerifyMapper 的方法返回类型必须 ⊆ {int, boolean, long}（只返标量、不返任何老人字段/PII）："
                        + violations);
    }

    @Test
    void noMethodReturnsPersonallyIdentifiableOrEntityTypes() {
        List<String> violations = new ArrayList<>();
        for (Method method : FamilyBindingVerifyMapper.class.getDeclaredMethods()) {
            Class<?> returnType = method.getReturnType();
            String typeName = returnType.getTypeName();
            boolean forbidden = FORBIDDEN_TYPES.contains(returnType);
            // 任何非标量的引用类型（Elder / FamilyBindingView / 集合 / String）一律禁止
            if (!returnType.isPrimitive()) {
                forbidden = true;
            }
            if (forbidden) {
                violations.add(method.getName() + " → " + typeName);
            }
        }
        assertEquals(
                List.of(),
                violations,
                "核验 Mapper 不得返回任何老人字段/PII（String/Elder/View/集合）：" + violations);
    }

    /**
     * 回归钉死：本 Mapper 必须携带接口级 {@link InterceptorIgnore}{@code (dataPermission = "1")} —— 这是设计 §10.8
     * 「唯一绕过第三闸的读」的<b>唯一落地机制</b>。
     *
     * <p>缺陷史（D-4 T08 实测暴露）：类 Javadoc 声称"绕过第三闸"，但接口<b>未带</b> {@code @InterceptorIgnore}。于是 scope=5
     * 的家属在 {@code POST /family/bindings} 核验时，其 {@code SELECT COUNT(*) FROM elder …} 被第三闸改写为 {@code
     * elder.id IN (SELECT b.elder_id FROM elder_family_bind … status=2 …)}（申请前恒空）→ 核验恒为 0 →
     * 申请<b>永远</b>返回 {@code 10004}。本测试防止有人再次删掉该注解而使申请链静默失效。
     */
    @Test
    void mapperIsExemptedFromDataPermissionThirdGate() {
        InterceptorIgnore ignore =
                FamilyBindingVerifyMapper.class.getAnnotation(InterceptorIgnore.class);
        assertNotNull(
                ignore,
                "FamilyBindingVerifyMapper 必须带 @InterceptorIgnore 才能绕过第三闸（否则 scope=5 家属申请恒失败）");
        assertTrue(
                "1".equals(ignore.dataPermission())
                        || "true".equalsIgnoreCase(ignore.dataPermission()),
                "@InterceptorIgnore 必须置 dataPermission=\"1\"（实际：" + ignore.dataPermission() + "）");
    }

    /**
     * ★C5 钉死：接口级 {@link InterceptorIgnore} 对<b>全部方法一次性生效</b> ⇒ 方法集合必须 ⊆ {@code {countExactMatch,
     * hasLoginAccount}}。
     *
     * <p>豁免的作用域是<b>接口</b>而非方法，因此将来任何人往本接口新增查询方法都会<b>静默继承</b>第三闸豁免（例如"顺手加一个按 id 查老人"）。本断言
     * 从<b>方法集合</b>层面封死这条后门：新增任何方法 → 变红；两个既有标量读方法缺失 → 同样变红。
     */
    @Test
    void mapperOnlyExposesScalarReadMethods() {
        Set<String> allowed = Set.of("countExactMatch", "hasLoginAccount");
        Set<String> actual =
                Arrays.stream(FamilyBindingVerifyMapper.class.getDeclaredMethods())
                        .map(Method::getName)
                        .collect(Collectors.toSet());

        assertTrue(
                allowed.containsAll(actual),
                "★C5：@InterceptorIgnore 为接口级、全方法生效 ⇒ 方法集合必须 ⊆ "
                        + allowed
                        + "（新增查询会静默继承豁免）：实际 "
                        + actual);
        assertTrue(
                actual.containsAll(allowed),
                "★C5：两个既有标量读方法必须存在（countExactMatch / hasLoginAccount）：实际 " + actual);
    }
}
