package cn.yl.modules.account.config;

import cn.yl.common.security.SensitiveFieldCodec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 敏感字段编解码器装配（B2，依赖 rpW1xZ 的绑定解析与后续档案域）。
 *
 * <p>**刻意做成条件装配**：密钥在 dev 环境默认为空，若无条件装配，{@code SensitiveFieldCodec}
 * 构造期即抛「密钥长度不足」而使应用启动失败——把「没配密钥」变成「起不来」是糟糕的默认值。改为： 两个密钥都非空才装配；未装配时依赖方（如 {@code
 * PhoneHashBindingResolver}）以「未配置」明确拒绝。
 *
 * <p>生产环境密钥由 KMS 注入（{@code SENSITIVE_FIELD_KEY} / {@code SENSITIVE_FIELD_HASH_KEY}），
 * 加密密钥与摘要密钥必须分离——同一密钥同时用于加密与检索哈希会削弱抗碰撞与最小暴露。
 */
@Configuration
public class SensitiveFieldConfig {

    @Bean
    @ConditionalOnExpression(
            "'${yl.security.sensitive-field-key:}'.length() > 0"
                    + " && '${yl.security.sensitive-field-hash-key:}'.length() > 0")
    public SensitiveFieldCodec sensitiveFieldCodec(
            @Value("${yl.security.sensitive-field-key:}") String masterKey,
            @Value("${yl.security.sensitive-field-hash-key:}") String hashKey) {
        return new SensitiveFieldCodec(masterKey, hashKey);
    }
}
