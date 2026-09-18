package cn.yl.common.security;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;

/**
 * 敏感字段编解码器（B1-4 / 子任务 rREFkt）。
 *
 * <p>算法：AES-256-GCM（认证加密，密文自带完整性校验）。 存储格式：{@code Base64(12字节IV || 密文 || 16字节GCM Tag)}，单列自包含，无需额外 IV
 * 列。
 *
 * <p>主密钥管理（ADR-011）：生产环境主密钥由云 KMS 托管，通过环境变量 {@code SENSITIVE_FIELD_KEY}
 * 注入本类，禁止硬编码、禁止入库。轮换时用旧密钥解密、新密钥加密（信封加密预留）。
 *
 * <p>等值检索：因 GCM 每次 IV 随机、密文不可比，故另存 HMAC-SHA256 摘要列（{@code *_hash}，见 02_schema.sql）
 * 供精确匹配（如身份证号去重）使用。摘要密钥与加密主密钥分离，防摘要反推。
 *
 * <p>合规依据：PRD §7「隐私展示」、§9「禁止身份证全文/人脸原图明文落库」。
 */
@Slf4j
public final class SensitiveFieldCodec {

    private static final String AES_GCM = "AES/GCM/NoPadding";
    private static final String HMAC_SHA256 = "HmacSHA256";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int AES_KEY_BITS = 256;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec aesKey;
    private final SecretKeySpec hmacKey;

    /**
     * @param base64MasterKey 主密钥（Base64 编码，≥32 字节），由 KMS/环境变量注入
     * @param base64HashKey 摘要密钥（Base64 编码），与加密密钥分离
     */
    public SensitiveFieldCodec(String base64MasterKey, String base64HashKey) {
        byte[] masterKey = requireKey(base64MasterKey, "SENSITIVE_FIELD_KEY");
        byte[] hashKey = requireKey(base64HashKey, "SENSITIVE_FIELD_HASH_KEY");
        this.aesKey = new SecretKeySpec(masterKey, "AES");
        this.hmacKey = new SecretKeySpec(hashKey, HMAC_SHA256);
    }

    private static byte[] requireKey(String base64Key, String name) {
        if (base64Key == null || base64Key.isBlank()) {
            throw new IllegalStateException(name + " 未配置：生产环境必须由 KMS 注入，禁止使用默认值");
        }
        byte[] key = Base64.getDecoder().decode(base64Key);
        if (key.length < AES_KEY_BITS / 8) {
            throw new IllegalStateException(name + " 长度不足：" + AES_KEY_BITS + " 位密钥需 ≥32 字节");
        }
        return key;
    }

    /**
     * 加密明文。
     *
     * @param plain 明文；null 或空串原样返回（不落空密文，便于查询判断）
     * @return Base64(IV || cipherText || tag)
     */
    public String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return plain;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] cipherText = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));

            byte[] out = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(cipherText, 0, out, iv.length, cipherText.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            // 不打印堆栈明细到标准错误，统一交由上层处理并记录（Checkstyle 禁止 printStackTrace）
            log.error("敏感字段加密失败", e);
            throw new IllegalStateException("敏感字段加密失败", e);
        }
    }

    /**
     * 解密。
     *
     * @param cipherText Base64(IV || cipherText || tag)
     * @return 明文；null 或空串原样返回
     */
    public String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) {
            return cipherText;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(cipherText);
            if (raw.length <= IV_LENGTH) {
                throw new IllegalArgumentException("密文长度非法");
            }
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(raw, 0, iv, 0, IV_LENGTH);
            Cipher cipher = Cipher.getInstance(AES_GCM);
            cipher.init(Cipher.DECRYPT_MODE, aesKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(raw, IV_LENGTH, raw.length - IV_LENGTH);
            return new String(plain, StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("敏感字段解密失败（可能密钥不匹配或数据被篡改）", e);
            throw new IllegalStateException("敏感字段解密失败", e);
        }
    }

    /**
     * 计算检索摘要（HMAC-SHA256 十六进制），写入 {@code *_hash} 列供等值查询。
     *
     * @param plain 明文
     * @return 64 位十六进制字符串
     */
    public String hashForSearch(String plain) {
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(hmacKey);
            byte[] digest = mac.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            log.error("敏感字段摘要计算失败", e);
            throw new IllegalStateException("敏感字段摘要计算失败", e);
        }
    }
}
