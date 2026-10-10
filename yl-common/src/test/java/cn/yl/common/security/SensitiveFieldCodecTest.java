package cn.yl.common.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * {@link SensitiveFieldCodec} 的行为门禁（P2-8-R10）。
 *
 * <h3>为什么必须有它</h3>
 *
 * <p>本类是全仓承载合规红线等级最高的一个类（手机号 / 身份证的 AES-GCM 加密 + HMAC 检索摘要）， 却在 2026-10-10 之前<b>零专属测试</b> ——
 * 只作为协作者被 {@code SmsCodeServiceTest} / {@code SuperviseExportServiceTest} 间接引用。 即「摘掉 HMAC、把 {@code
 * encrypt} 换成恒等返回」不会有任何测试变红。本类补上这块盲区。
 *
 * <h3>覆盖的红线类别（{@code docs/quality/red-line-guard-mutation-proof.md} §2.1）</h3>
 *
 * <ul>
 *   <li><b>R1 加密 / 密钥</b>：往返自洽、密文不含明文、每次新 IV、篡改必被检出（绝不静默返回）、密钥缺失 fail-closed；
 *   <li><b>R2 哈希 / 摘要</b>：摘要确定性、<b>有密钥</b>（钉死 HMAC 向量 ≠ 裸 SHA-256）、密钥分离。
 * </ul>
 *
 * <p>断言一律<b>钉死期望值</b>（已知向量），不用「不等于明文」这类弱否定 —— 弱否定可被"另一个错值"绕过。
 */
class SensitiveFieldCodecTest {

    // 固定测试密钥（32 字节，仅测试用；生产由 KMS/环境变量注入）
    private static final String MASTER_B64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";
    private static final String HASH_B64 = "ZmVkY2JhOTg3NjU0MzIxMGZlZGNiYTk4NzY1NDMyMTA=";

    private static final String PHONE = "13800138000";
    private static final String ID_CARD = "530300199001011234";

    /** HMAC-SHA256(HASH_KEY, PHONE) 的已知向量（独立算得，钉死用）。 */
    private static final String PHONE_HMAC =
            "08b1c9ea95f3b59ac21cc6c9fa328ce370533adeaebda2d4d903ec3e9c7d3e4d";

    /** SHA-256(PHONE) —— 无密钥摘要，用于证明 {@code hashForSearch} **不是**裸哈希。 */
    private static final String PHONE_SHA256 =
            "a6942f9771d67f34034d2f1926988ed3fad3bf1b4e7cedb9a31f31398dea43bc";

    private static final String ID_CARD_HMAC =
            "231d54b440c8dde811f04a24ecf90b9a28ffbdcafbe8394b53eb21651109f7ac";

    private static SensitiveFieldCodec codec() {
        return new SensitiveFieldCodec(MASTER_B64, HASH_B64);
    }

    private static String sha256Hex(String plain) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        StringBuilder sb = new StringBuilder();
        for (byte b : digest.digest(plain.getBytes(StandardCharsets.UTF_8))) {
            sb.append(Character.forDigit((b >> 4) & 0xF, 16));
            sb.append(Character.forDigit(b & 0xF, 16));
        }
        return sb.toString();
    }

    @Nested
    @DisplayName("R1 加密 / 密钥")
    class Encryption {

        @Test
        @DisplayName("加解密往返自洽（手机号与身份证号）")
        void roundTripIsLossless() {
            SensitiveFieldCodec codec = codec();

            assertThat(codec.decrypt(codec.encrypt(PHONE))).isEqualTo(PHONE);
            assertThat(codec.decrypt(codec.encrypt(ID_CARD))).isEqualTo(ID_CARD);
            assertThat(codec.decrypt(codec.encrypt("中文姓名·张三"))).isEqualTo("中文姓名·张三");
        }

        @Test
        @DisplayName("★ 密文不得包含明文（按 UTF-8 字节逐序列比对，而非只比字符串）")
        void cipherTextNeverCarriesPlainBytes() {
            SensitiveFieldCodec codec = codec();

            String cipher = codec.encrypt(PHONE);
            byte[] raw = Base64.getDecoder().decode(cipher);
            byte[] plainBytes = PHONE.getBytes(StandardCharsets.UTF_8);

            assertThat(cipher).isNotEqualTo(PHONE);
            assertThat(indexOf(raw, plainBytes)).as("密文字节流中不得出现明文序列").isEqualTo(-1);
            assertThat(cipher).doesNotContain(PHONE);
        }

        @Test
        @DisplayName("★ 每次加密使用新 IV：同一明文两次密文不同，且都能解回原值")
        void eachEncryptionUsesFreshIv() {
            SensitiveFieldCodec codec = codec();

            String first = codec.encrypt(PHONE);
            String second = codec.encrypt(PHONE);

            assertThat(first).as("随机 IV 使密文不可比（否则等值关系被密文泄露）").isNotEqualTo(second);
            assertThat(codec.decrypt(first)).isEqualTo(PHONE);
            assertThat(codec.decrypt(second)).isEqualTo(PHONE);
        }

        @Test
        @DisplayName("★ 篡改必被检出：翻转密文一字节 → 抛错，绝不静默返回（GCM Tag 是认证加密的核心）")
        void tamperIsRejectedNotSilentlyReturned() {
            SensitiveFieldCodec codec = codec();
            byte[] raw = Base64.getDecoder().decode(codec.encrypt(PHONE));

            // 翻转最后一个字节（落在 GCM Tag 内）
            raw[raw.length - 1] ^= 0x01;
            String tampered = Base64.getEncoder().encodeToString(raw);

            assertThatThrownBy(() -> codec.decrypt(tampered))
                    .as("认证加密被摘掉（如换成 AES/CTR）时本断言必须变红")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("解密失败");
        }

        @Test
        @DisplayName("★ 换密钥解不开（密钥不匹配同样走失败路径，不得回退成密文/明文）")
        void wrongKeyCannotDecrypt() {
            String cipher = codec().encrypt(PHONE);
            SensitiveFieldCodec otherKey =
                    new SensitiveFieldCodec(
                            Base64.getEncoder()
                                    .encodeToString(
                                            "OTHER-KEY-OTHER-KEY-0123456789ab"
                                                    .getBytes(StandardCharsets.UTF_8)),
                            HASH_B64);

            assertThatThrownBy(() -> otherKey.decrypt(cipher))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("解密失败");
        }

        @Test
        @DisplayName("★ 密钥缺失 / 过短 → 构造即失败（fail-closed，绝不退化成明文透传）")
        void constructorFailsClosedOnBadKeys() {
            assertThatThrownBy(() -> new SensitiveFieldCodec(null, HASH_B64))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SENSITIVE_FIELD_KEY");
            assertThatThrownBy(() -> new SensitiveFieldCodec("  ", HASH_B64))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SENSITIVE_FIELD_KEY");
            assertThatThrownBy(() -> new SensitiveFieldCodec(MASTER_B64, null))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("SENSITIVE_FIELD_HASH_KEY");
            // 16 字节 < 32 字节要求
            String shortKey = Base64.getEncoder().encodeToString(new byte[16]);
            assertThatThrownBy(() -> new SensitiveFieldCodec(shortKey, HASH_B64))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("长度不足");
        }

        @Test
        @DisplayName("★ 主密钥必须恰为 32 字节 → 否则构造即失败（P2-8-R13：原先只校验「≥32」⇒ 更长的主密钥要到首次加解密才炸）")
        void masterKeyLengthMustBeExactly32() {
            String tooLong = Base64.getEncoder().encodeToString(new byte[33]);
            String muchTooLong = Base64.getEncoder().encodeToString(new byte[64]);

            assertThatThrownBy(() -> new SensitiveFieldCodec(tooLong, HASH_B64))
                    .as("33 字节主密钥：AES-256 不接受，必须 fail-fast")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("长度非法");
            assertThatThrownBy(() -> new SensitiveFieldCodec(muchTooLong, HASH_B64))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("长度非法");
            // 恰 32 字节正常
            assertThatCode(() -> new SensitiveFieldCodec(MASTER_B64, HASH_B64))
                    .doesNotThrowAnyException();
            // 摘要密钥无上限问题：48 字节仍可用
            assertThatCode(
                            () ->
                                    new SensitiveFieldCodec(
                                            MASTER_B64,
                                            Base64.getEncoder().encodeToString(new byte[48])))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("null / 空串原样返回（已声明的口径：不落空密文，便于查询判断）")
        void nullAndEmptyPassThrough() {
            SensitiveFieldCodec codec = codec();

            assertThat(codec.encrypt(null)).isNull();
            assertThat(codec.encrypt("")).isEmpty();
            assertThat(codec.decrypt(null)).isNull();
            assertThat(codec.decrypt("")).isEmpty();
        }

        @Test
        @DisplayName("★ 换一把主密钥仍各自自洽：另一把 32 字节主密钥加密的密文能被同一把密钥解回原值")
        void alternateMasterKeyRoundTripsWithinItself() {
            SensitiveFieldCodec other =
                    new SensitiveFieldCodec(
                            Base64.getEncoder()
                                    .encodeToString(
                                            "OTHER-MASTER-KEY-0123456789abcde"
                                                    .getBytes(StandardCharsets.UTF_8)),
                            HASH_B64);

            assertThat(other.decrypt(other.encrypt(PHONE))).isEqualTo(PHONE);
            assertThat(other.decrypt(other.encrypt(ID_CARD))).isEqualTo(ID_CARD);
        }
    }

    @Nested
    @DisplayName("R2 哈希 / 摘要")
    class Hashing {

        @Test
        @DisplayName("摘要确定且为 64 位小写十六进制")
        void digestIsDeterministicAndHex() {
            SensitiveFieldCodec codec = codec();

            String first = codec.hashForSearch(PHONE);
            String second = codec.hashForSearch(PHONE);

            assertThat(first).isEqualTo(second).hasSize(64).matches("[0-9a-f]{64}");
        }

        @Test
        @DisplayName("★ 摘要是「有密钥」的：必须等于已知 HMAC 向量，且不等于裸 SHA-256")
        void digestIsKeyedNotPlainSha256() throws Exception {
            SensitiveFieldCodec codec = codec();

            // 钉死期望值：改坏 HMAC（换算法 / 丢密钥）会让这里立刻变红
            assertThat(codec.hashForSearch(PHONE)).isEqualTo(PHONE_HMAC);
            assertThat(codec.hashForSearch(ID_CARD)).isEqualTo(ID_CARD_HMAC);

            // 显式证伪"退化成无密钥摘要"：裸 SHA-256 是确定的另一串值
            assertThat(codec.hashForSearch(PHONE))
                    .as("摘要必须依赖 HASH_KEY；等于裸 SHA-256 即密钥分离失效")
                    .isNotEqualTo(sha256Hex(PHONE));
            assertThat(sha256Hex(PHONE)).isEqualTo(PHONE_SHA256);
        }

        @Test
        @DisplayName("★ 摘要密钥分离：换 hashKey → 同明文摘要不同（防摘要反推）")
        void digestDependsOnHashKey() {
            SensitiveFieldCodec a = codec();
            SensitiveFieldCodec b =
                    new SensitiveFieldCodec(
                            MASTER_B64,
                            Base64.getEncoder()
                                    .encodeToString(
                                            "SECOND-HASH-KEY-0123456789abcdef"
                                                    .getBytes(StandardCharsets.UTF_8)));

            assertThat(a.hashForSearch(PHONE)).isNotEqualTo(b.hashForSearch(PHONE));
        }

        @Test
        @DisplayName("同明文不同填充/大小写输入应产出不同摘要（不做静默归一化）")
        void digestIsNotNormalised() {
            SensitiveFieldCodec codec = codec();

            assertThat(codec.hashForSearch("13800138000"))
                    .isNotEqualTo(codec.hashForSearch(" 13800138000 "));
        }

        @Test
        @DisplayName("null / 空串摘要为 null（调用方据此判断『无值』，不给可检索的空摘要）")
        void nullAndEmptyHaveNoDigest() {
            SensitiveFieldCodec codec = codec();

            assertThat(codec.hashForSearch(null)).isNull();
            assertThat(codec.hashForSearch("")).isNull();
        }
    }

    @Test
    @DisplayName("★ 密钥分离：改 masterKey 不影响摘要（摘要是有密钥的 HMAC，用的是独立 hashKey）")
    void masterKeyDoesNotAffectDigest() {
        // ★ 本用例**只**断言摘要。加密往返已移入 R1 组的
        //   alternateMasterKeyRoundTripsWithinItself —— 否则一条「摘要」用例会被
        //   **加密**类突变打红，红点名与真实缺陷错位（P2-8-R10 复跑时实测到该错位）。
        SensitiveFieldCodec b =
                new SensitiveFieldCodec(
                        Base64.getEncoder()
                                .encodeToString(
                                        "OTHER-MASTER-KEY-0123456789abcde"
                                                .getBytes(StandardCharsets.UTF_8)),
                        HASH_B64);

        assertThat(b.hashForSearch(PHONE)).isEqualTo(codec().hashForSearch(PHONE));
        assertThat(b.hashForSearch(ID_CARD)).isEqualTo(ID_CARD_HMAC);
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
