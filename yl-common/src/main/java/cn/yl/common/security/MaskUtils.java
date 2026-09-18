package cn.yl.common.security;

/**
 * 脱敏工具（B1-2/B1-4 共用）。
 *
 * <p>用于默认响应脱敏（PRD §7「隐私展示」：身份证/手机号/健康信息默认脱敏，如 {@code 138****5678}）。 明文仅可通过「二次验证 + 留痕」的专门接口获取。
 */
public final class MaskUtils {

    private MaskUtils() {}

    /**
     * 手机号脱敏：保留前 3 后 4。
     *
     * @param phone 手机号
     * @return 如 {@code 138****5678}
     */
    public static String maskPhone(String phone) {
        if (phone == null || phone.length() < 7) {
            return phone;
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    /**
     * 身份证脱敏：保留前 4 后 4（不暴露出生日期段）。
     *
     * @param idCard 身份证号
     * @return 如 {@code 5301**********1234}
     */
    public static String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() < 9) {
            return idCard;
        }
        int keepHead = 4;
        int keepTail = 4;
        int maskLen = idCard.length() - keepHead - keepTail;
        return idCard.substring(0, keepHead)
                + "*".repeat(Math.max(maskLen, 0))
                + idCard.substring(idCard.length() - keepTail);
    }

    /**
     * 姓名脱敏：保留姓氏，其余以 * 代替。
     *
     * @param name 姓名
     * @return 如 {@code 张*}；复姓/少数民族姓名保留首字
     */
    public static String maskName(String name) {
        if (name == null || name.isEmpty()) {
            return name;
        }
        if (name.length() == 1) {
            return name;
        }
        return name.charAt(0) + "*".repeat(name.length() - 1);
    }

    /** 通用脱敏：保留首尾若干字符，中间以 * 填充。 */
    public static String maskMiddle(String value, int keepHead, int keepTail) {
        if (value == null) {
            return null;
        }
        if (value.length() <= keepHead + keepTail) {
            return "*".repeat(value.length());
        }
        int maskLen = value.length() - keepHead - keepTail;
        return value.substring(0, keepHead)
                + "*".repeat(maskLen)
                + value.substring(value.length() - keepTail);
    }
}
