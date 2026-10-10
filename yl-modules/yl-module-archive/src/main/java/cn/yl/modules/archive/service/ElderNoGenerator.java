package cn.yl.modules.archive.service;

import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;

/**
 * 档案号生成器（{@code elder_no}）。
 *
 * <p><b>生成规则（D-2 设计 §10 待明确 #5 定稿）</b>：{@code "ELD" + yyyyMMdd + 6 位随机数字}， 长度 {@code 3 + 8 + 6 =
 * 17}，满足列定义 {@code VARCHAR(32)}。档案号为<b>非隐私</b>对外编号， 不含姓名/身份证等任何敏感信息，可用于列表展示与外部引用。
 *
 * <p><b>唯一性</b>：规则本身不保证唯一，唯一性由 {@code elder.uk_elder_no} 唯一键兜底， 并由 {@link ElderArchiveService}
 * 在插入冲突时<b>重试</b>（最多 3 次，仍冲突则抛 {@link cn.yl.common.exception.ErrorCode#CONFLICT} = 10005）。
 */
@Component
public class ElderNoGenerator {

    /** 档案号前缀。 */
    private static final String PREFIX = "ELD";

    private static final DateTimeFormatter DAY_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    /** 随机后缀上界（补齐为 6 位）。 */
    private static final int SUFFIX_BOUND = 1_000_000;

    private static final String ZERO_PAD = "000000";

    private static final SecureRandom RANDOM = new SecureRandom();

    /**
     * 生成一个新的档案号。
     *
     * @return 形如 {@code ELD20260924000123} 的 17 位档案号
     */
    public String next() {
        String suffix = Integer.toString(RANDOM.nextInt(SUFFIX_BOUND));
        return PREFIX
                + LocalDate.now().format(DAY_FORMAT)
                + ZERO_PAD.substring(suffix.length())
                + suffix;
    }
}
