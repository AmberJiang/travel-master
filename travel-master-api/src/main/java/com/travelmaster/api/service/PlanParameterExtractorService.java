package com.travelmaster.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmaster.api.config.DeepSeekProperties;
import com.travelmaster.api.dto.ExtractedPlanParams;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 从用户一句话中抽取目的地、天数与调整说明，供对话入口触发规划师。
 */
@Service
public class PlanParameterExtractorService {

    private static final Logger log = LoggerFactory.getLogger(PlanParameterExtractorService.class);

    private static final String SYSTEM = """
            你是参数抽取器，只输出 JSON，不聊天。
            从用户话里提取：目的地（城市/地区/国家内区域名）、整数天数、若为改行程则 adjustmentHint 写用户具体要求否则空字符串。
            输出格式严格为：
            {"destination":"字符串或空","days":正整数或null,"adjustmentHint":"字符串"}
            规则：无法确定天数则 days 为 null；无法确定目的地则 destination 为空字符串。
            """;

    private final DeepSeekProperties deepSeekProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;

    public PlanParameterExtractorService(DeepSeekProperties deepSeekProperties, ObjectMapper objectMapper) {
        this.deepSeekProperties = deepSeekProperties;
        this.objectMapper = objectMapper;
        this.restClient = RestClient.builder()
                .baseUrl(deepSeekProperties.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public ExtractedPlanParams extract(String userMessage) {
        if (!StringUtils.hasText(deepSeekProperties.apiKey()) || !StringUtils.hasText(userMessage)) {
            return new ExtractedPlanParams("", null, "");
        }
        try {
            Map<String, Object> payload = Map.of(
                    "model", deepSeekProperties.model(),
                    "temperature", 0.0,
                    "max_tokens", 180,
                    "messages", List.of(
                            Map.of("role", "system", "content", SYSTEM),
                            Map.of("role", "user", "content", userMessage.trim())
                    )
            );
            String raw = restClient.post()
                    .uri(deepSeekProperties.chatPath())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + deepSeekProperties.apiKey())
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(raw);
            String content = root.path("choices").path(0).path("message").path("content").asText("").trim();
            JsonNode j = objectMapper.readTree(content);
            String dest = j.path("destination").asText("").trim();
            Integer days = null;
            if (j.has("days") && !j.get("days").isNull()) {
                int d = j.get("days").asInt(0);
                if (d > 0) {
                    days = d;
                }
            }
            String hint = j.path("adjustmentHint").asText("").trim();
            return new ExtractedPlanParams(dest, days, hint);
        } catch (Exception ex) {
            log.warn("规划参数抽取失败", ex);
            return new ExtractedPlanParams("", null, "");
        }
    }
}
