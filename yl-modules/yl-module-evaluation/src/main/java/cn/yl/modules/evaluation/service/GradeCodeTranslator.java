package cn.yl.modules.evaluation.service;

/**
 * 等级码翻译（引擎 ↔ 数据库）。
 *
 * <p>引擎（权威规则集）输出 L0~L4；数据库 {@code eval_order.grade_code} 与 {@code gb_grade_threshold} 使用 GRADE_*
 * 语义码。两者区间一致（L1 66~89 ↔ GRADE_MILD 66.00~89.99），此处仅做码值翻译，不含任何阈值判断—— 阈值唯一来源是 RuleSet JSON。
 */
public final class GradeCodeTranslator {

    private GradeCodeTranslator() {}

    /**
     * 引擎等级码 → 数据库等级码。
     *
     * @param levelCode 引擎等级码（L0~L4）
     * @return 数据库等级码（GRADE_*）
     * @throws IllegalArgumentException 未知等级码（说明规则集与字典不同步，属致命配置错误）
     */
    public static String toGradeCode(String levelCode) {
        return switch (levelCode) {
            case "L0" -> "GRADE_INTACT";
            case "L1" -> "GRADE_MILD";
            case "L2" -> "GRADE_MODERATE";
            case "L3" -> "GRADE_SEVERE";
            case "L4" -> "GRADE_TOTAL";
            default -> throw new IllegalArgumentException("未知等级码（规则集与等级字典不同步）: " + levelCode);
        };
    }

    /**
     * 指标 id → 维度码（1~4）。规则集指标 id 采用百位分区：1xx-自理能力、2xx-基础运动、3xx-精神状态、4xx-感知觉与社会参与。
     *
     * @param itemId 指标 id（101~405）
     * @return 维度码 1~4
     * @throws IllegalArgumentException 指标 id 不在 101~499 区间
     */
    public static int toDimensionCode(long itemId) {
        if (itemId < 101 || itemId > 499) {
            throw new IllegalArgumentException("指标 id 超出合法区间 101~499: " + itemId);
        }
        return (int) (itemId / 100);
    }
}
