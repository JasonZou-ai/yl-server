package cn.yl.modules.evaluation.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * 规则集装载器：从 classpath 读取 JSON，计算 SHA-256 内容指纹后反序列化，并惰性校验结构。
 *
 * <p>指纹（ruleSha）随规则集一并写入 {@link RuleSet}，供报告绑定、支持事后同版本复算。
 */
public final class RuleSetLoader {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RuleSetLoader() {}

    public static RuleSet loadFromClasspath(String resourcePath) throws IOException {
        try (InputStream in =
                RuleSetLoader.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalArgumentException("规则集资源未找到: " + resourcePath);
            }
            byte[] bytes = in.readAllBytes();
            String sha = sha256(bytes);
            RuleSet rs = MAPPER.readValue(bytes, RuleSet.class);
            rs.setRuleSha(sha);
            return rs;
        }
    }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(data);
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 算法不可用", e);
        }
    }
}
