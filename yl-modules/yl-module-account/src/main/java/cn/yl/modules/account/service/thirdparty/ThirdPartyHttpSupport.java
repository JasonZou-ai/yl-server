package cn.yl.modules.account.service.thirdparty;

import cn.yl.common.exception.BizException;
import cn.yl.common.exception.ErrorCode;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * 第三方平台 HTTP 支撑（B2 / rpW1xZ）。
 *
 * <p>统一收敛平台调用的三件事：URL 参数编码、JSON 解析、失败归类。平台返回的原始报文**不进日志** （可能含 openId / session_key
 * 等敏感值），失败时只上抛笼统业务码。
 */
@Component
@RequiredArgsConstructor(
        onConstructor_ =
                @SuppressFBWarnings(
                        value = "EI_EXPOSE_REP2",
                        justification = "ObjectMapper 是 Spring 容器管理的线程安全共享 Bean，构造器注入是标准 DI 用法"))
public class ThirdPartyHttpSupport {

    private final ObjectMapper objectMapper;

    private final RestClient restClient = RestClient.create();

    /**
     * GET 并解析 JSON。
     *
     * @param url 完整 URL（含查询串）
     */
    public JsonNode getJson(String url) {
        try {
            String body = restClient.get().uri(url).retrieve().body(String.class);
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (RuntimeException | JsonProcessingException e) {
            throw new BizException(ErrorCode.LOGIN_FAILED, "第三方平台调用失败，请稍后重试");
        }
    }

    /**
     * POST JSON 并解析 JSON。
     *
     * @param url 完整 URL
     * @param jsonBody 已序列化的 JSON 请求体
     */
    public JsonNode postJson(String url, String jsonBody) {
        try {
            String body =
                    restClient
                            .post()
                            .uri(url)
                            .contentType(MediaType.APPLICATION_JSON)
                            .body(jsonBody)
                            .retrieve()
                            .body(String.class);
            return objectMapper.readTree(body == null ? "{}" : body);
        } catch (RuntimeException | JsonProcessingException e) {
            throw new BizException(ErrorCode.LOGIN_FAILED, "第三方平台调用失败，请稍后重试");
        }
    }

    /** 序列化请求体；失败归为参数错误。 */
    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请求参数序列化失败");
        }
    }

    /** 读取文本字段；缺失返回 null。 */
    public static String text(JsonNode node, String field) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    /** 沿字段路径读取文本（如 {@code phone_info.purePhoneNumber}）。 */
    public static String path(JsonNode node, String... fields) {
        JsonNode current = node;
        for (String field : fields) {
            current = current == null ? null : current.get(field);
        }
        return current == null || current.isNull() ? null : current.asText();
    }

    /** URL 参数编码。 */
    public static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
