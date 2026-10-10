package cn.yl.modules.archive.support;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import cn.yl.common.security.MaskUtils;
import cn.yl.common.security.SensitiveFieldCodec;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * 敏感字段支撑（档案域专用）：包装 {@link SensitiveFieldCodec} 的<b>可选</b>依赖与统一脱敏入口。
 *
 * <p><b>为什么用 {@link ObjectProvider} 而非直接注入</b>：{@code SensitiveFieldCodec} 在 {@code
 * cn.yl.modules.account.config.SensitiveFieldConfig} 中是<b>条件装配</b>（双密钥非空才注册）， 而 archive 模块只依赖
 * {@code yl-api}（{@code yl-common} 传递依赖），<b>不能编译期引用 account 模块</b>。 故以 {@code ObjectProvider}
 * 可选注入，在 dev（未配密钥）下 {@code isAvailable() == false}。
 *
 * <p><b>未装配（dev 无密钥）时的拒绝口径</b>：{@link #encrypt(String)} / {@link #hashForSearch(String)} / {@link
 * #decrypt(String)} 一律抛 {@link BizException}({@code ARCHIVE_SENSITIVE_ENCRYPT_FAILED = 40001})——
 * <b>禁止明文落库，也不允许应用启动失败</b>（沿用 {@code SensitiveFieldConfig}「不配密钥 ≠ 起不来」的取舍）。
 */
@Component
@RequiredArgsConstructor
public class SensitiveFieldSupport {

    private static final String UNAVAILABLE_REASON =
            "敏感字段编解码器未装配（dev 未配置 SENSITIVE_FIELD_KEY / SENSITIVE_FIELD_HASH_KEY）；" + "禁止明文落库";

    private final ObjectProvider<SensitiveFieldCodec> codecProvider;

    /** 编解码器是否已装配（dev 无密钥时为 false）。 */
    public boolean isAvailable() {
        return codecProvider.getIfAvailable() != null;
    }

    /**
     * 加密明文。
     *
     * @param plain 明文；null 或空串原样返回（不落空密文）
     * @return 密文
     * @throws BizException 编解码器未装配（40001）
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        return requireCodec().encrypt(plain);
    }

    /**
     * 计算等值检索摘要（HMAC-SHA256）。
     *
     * @param plain 明文；null 或空串返回 null
     * @return 摘要；或 null
     * @throws BizException 编解码器未装配（40001）
     */
    public String hashForSearch(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        return requireCodec().hashForSearch(plain);
    }

    /**
     * 解密密文。
     *
     * @param cipherText 密文；null 或空串原样返回
     * @return 明文
     * @throws BizException 编解码器未装配（40001）
     */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) {
            return cipherText;
        }
        return requireCodec().decrypt(cipherText);
    }

    /** 姓名脱敏（委托 {@link MaskUtils}）。 */
    public String maskName(String name) {
        return MaskUtils.maskName(name);
    }

    /** 身份证脱敏（委托 {@link MaskUtils}）。 */
    public String maskIdCard(String idCard) {
        return MaskUtils.maskIdCard(idCard);
    }

    /** 手机号脱敏（委托 {@link MaskUtils}）。 */
    public String maskPhone(String phone) {
        return MaskUtils.maskPhone(phone);
    }

    /** 取编解码器；未装配即抛 40001（拒绝口径，不静默降级为明文）。 */
    private SensitiveFieldCodec requireCodec() {
        SensitiveFieldCodec codec = codecProvider.getIfAvailable();
        if (codec == null) {
            throw new BizException(ErrorCode.ARCHIVE_SENSITIVE_ENCRYPT_FAILED, UNAVAILABLE_REASON);
        }
        return codec;
    }
}
