package cn.yl.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * 短信 dev 验证码打印开关的配置门禁（P2-8-A，设计 §4.6 / §7.8）。
 *
 * <p>把「生产不得打印验证码」从口头承诺变成可回归断言：非 dev 的 {@code application.yml} 中 {@code yl.sms.dev-log-code} 必须为
 * {@code false}；联调由 {@code application-dev.yml} 置 {@code true}。
 */
class SmsDevLogCodeConfigTest {

    @Test
    void productionConfigKeepsDevLogCodeFalse() throws IOException {
        Object value = devLogCode(load("application.yml"));
        assertEquals(Boolean.FALSE, value, "application.yml 的 yl.sms.dev-log-code 必须为 false");
    }

    @Test
    void devProfileEnablesDevLogCode() throws IOException {
        Object value = devLogCode(load("application-dev.yml"));
        assertEquals(Boolean.TRUE, value, "application-dev.yml 的 yl.sms.dev-log-code 应为 true");
    }

    @SuppressWarnings("unchecked")
    private static Object devLogCode(Map<String, Object> root) {
        Map<String, Object> yl = (Map<String, Object>) root.get("yl");
        assertNotNull(yl, "缺少 yl 配置段");
        Map<String, Object> sms = (Map<String, Object>) yl.get("sms");
        assertNotNull(sms, "缺少 yl.sms 配置段");
        return sms.get("dev-log-code");
    }

    private static Map<String, Object> load(String name) throws IOException {
        try (BufferedReader reader = Files.newBufferedReader(locate(name))) {
            return new Yaml().load(reader);
        }
    }

    /** 从当前工作目录向上查找模块资源文件（surefire 的工作目录是模块目录 yl-bootstrap）。 */
    private static Path locate(String name) {
        Path dir = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 4 && dir != null; i++) {
            Path direct = dir.resolve("src/main/resources").resolve(name);
            if (Files.isRegularFile(direct)) {
                return direct;
            }
            Path nested = dir.resolve("yl-bootstrap/src/main/resources").resolve(name);
            if (Files.isRegularFile(nested)) {
                return nested;
            }
            dir = dir.getParent();
        }
        throw new IllegalStateException("未找到资源文件：" + name);
    }
}
