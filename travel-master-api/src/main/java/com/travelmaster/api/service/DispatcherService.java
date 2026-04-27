package com.travelmaster.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmaster.api.config.DeepSeekProperties;
import com.travelmaster.api.dto.DispatchRequest;
import com.travelmaster.api.dto.DispatchResponse;
import com.travelmaster.api.dto.IntentType;
import io.modelcontextprotocol.client.McpAsyncClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.lang.reflect.Method;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class DispatcherService {

    private static final String SYSTEM_PROMPT = """
            你是旅行应用的入口调度器，只做意图识别，不聊天。
            任务：
            1) 过滤寒暄和无效废话，只保留对意图有帮助的关键信息。
            2) 判断用户意图 intent，只能是 QUESTION、REPLAN_ROUTE、OTHER 三选一。
            3) 输出严格 JSON，不要输出 markdown，不要额外文本。
            输出格式：
            {"intent":"QUESTION|REPLAN_ROUTE|OTHER","reasoning":"不超过25字中文"}
            判定规则：
            - 问知识、攻略、建议、解释 => QUESTION
            - 提到改行程、重排路线、调整景点顺序/天数/交通 => REPLAN_ROUTE
            - 其他或无法判断 => OTHER
            """;

    private final DeepSeekProperties deepSeekProperties;
    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final List<McpAsyncClient> mcpAsyncClients;

    public DispatcherService(DeepSeekProperties deepSeekProperties,
                             ObjectMapper objectMapper,
                             ObjectProvider<List<McpAsyncClient>> mcpAsyncClientsProvider) {
        this.deepSeekProperties = deepSeekProperties;
        this.objectMapper = objectMapper;
        this.mcpAsyncClients = mcpAsyncClientsProvider.getIfAvailable(ArrayList::new);
        this.restClient = RestClient.builder()
                .baseUrl(deepSeekProperties.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public DispatchResponse dispatch(DispatchRequest request) {
        String normalized = normalize(request.message());
        String mcpObservation = inspectMcpTools();

        if (!StringUtils.hasText(deepSeekProperties.apiKey())) {
            return buildFallback(request.userId(), normalized, "未配置 DEEPSEEK_API_KEY；" + mcpObservation);
        }

        try {
            String userPrompt = normalized + "\n\nMCP状态：" + mcpObservation;
            Map<String, Object> payload = Map.of(
                    "model", deepSeekProperties.model(),
                    "temperature", 0.0,
                    "max_tokens", 120,
                    "messages", List.of(
                            Map.of("role", "system", "content", SYSTEM_PROMPT),
                            Map.of("role", "user", "content", userPrompt)
                    )
            );

            String rawResponse = restClient.post()
                    .uri(deepSeekProperties.chatPath())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + deepSeekProperties.apiKey())
                    .body(payload)
                    .retrieve()
                    .body(String.class);

            return parseDispatchResult(request.userId(), normalized, mcpObservation, rawResponse);
        } catch (Exception ex) {
            return buildFallback(request.userId(), normalized, "模型调用失败，已降级；" + mcpObservation);
        }
    }

    private DispatchResponse parseDispatchResult(String userId, String normalized, String mcpObservation, String rawResponse) throws Exception {
        JsonNode root = objectMapper.readTree(rawResponse);
        String content = root.path("choices").path(0).path("message").path("content").asText("");
        JsonNode decision = objectMapper.readTree(content);
        String intentText = decision.path("intent").asText("OTHER");
        String reasoning = decision.path("reasoning").asText("无法稳定识别，已降级") + "；" + mcpObservation;

        IntentType intent = parseIntent(intentText);
        return new DispatchResponse(
                UUID.randomUUID().toString(),
                userId,
                normalized,
                intent,
                reasoning,
                resolveAgent(intent),
                Instant.now()
        );
    }

    private DispatchResponse buildFallback(String userId, String normalized, String reason) {
        IntentType intent = IntentType.OTHER;
        return new DispatchResponse(
                UUID.randomUUID().toString(),
                userId,
                normalized,
                intent,
                reason,
                resolveAgent(intent),
                Instant.now()
        );
    }

    private String inspectMcpTools() {
        if (mcpAsyncClients.isEmpty()) {
            return "MCP未连接";
        }
        try {
            McpAsyncClient client = mcpAsyncClients.get(0);
            Method initializeMethod = client.getClass().getMethod("initialize");
            Object initializeMono = initializeMethod.invoke(client);
            Method blockInitMethod = initializeMono.getClass().getMethod("block", Duration.class);
            blockInitMethod.invoke(initializeMono, Duration.ofSeconds(6));

            Method listToolsMethod = client.getClass().getMethod("listTools");
            Object monoResult = listToolsMethod.invoke(client);

            Method blockMethod = monoResult.getClass().getMethod("block", Duration.class);
            Object toolsResult = blockMethod.invoke(monoResult, Duration.ofSeconds(6));
            if (toolsResult == null) {
                return "MCP已连接，tools为空";
            }

            Method toolsMethod = toolsResult.getClass().getMethod("tools");
            Object tools = toolsMethod.invoke(toolsResult);
            if (tools instanceof List<?> toolList) {
                return "MCP已连接，tools=" + toolList.size();
            }
            return "MCP已连接";
        } catch (Exception ex) {
            return "MCP调用失败";
        }
    }

    private String normalize(String message) {
        return message.trim().replaceAll("\\s+", " ");
    }

    private IntentType parseIntent(String intentText) {
        return switch (intentText) {
            case "QUESTION" -> IntentType.QUESTION;
            case "REPLAN_ROUTE" -> IntentType.REPLAN_ROUTE;
            default -> IntentType.OTHER;
        };
    }

    private String resolveAgent(IntentType intent) {
        return switch (intent) {
            case QUESTION -> "knowledge-agent";
            case REPLAN_ROUTE -> "route-planner-agent";
            case OTHER -> "fallback-agent";
        };
    }
}
