package com.travelmaster.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmaster.api.config.DeepSeekProperties;
import com.travelmaster.api.dto.PlanDraftRequest;
import com.travelmaster.api.dto.PlanDraftResponse;
import com.travelmaster.api.dto.RagCitation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 路线规划师：不直接做百科式问答；根据目的地与天数从向量库取素材，再生成按天行程草案。
 */
@Service
public class PlannerService {

    private static final Logger log = LoggerFactory.getLogger(PlannerService.class);

    private static final String PLANNER_SYSTEM_PROMPT = """
            你是 Travel Master 的「路线规划师」，只做一件事：根据给定的「知识库素材」和「目的地、天数、用户备注」输出一份逻辑严密的行程草案。
            硬性规则：
            1) 不得编造素材中未出现的具体事实（店名、票价、营业时间、地址以素材为准；素材没有的不要写死数字）。
            2) 若素材不足以支撑某天安排，在该天末尾用一句话标注「素材不足：可补充××类信息」，不要虚构细节填空。
            3) 输出简体中文；按「第1天…第N天」分天写；每天内按上午/下午/晚间分段，注意地理聚类（同一天内尽量减少跨区折返，在素材能推断区域时体现）。
            4) 你不是问答机器人：不要回答与行程草案无关的泛化攻略问句；不要输出 JSON；不要用 markdown 代码块包裹全文。
            5) 草案末可附「素材中未覆盖」的简短列表，便于后续补库。
            """;

    private final DeepSeekProperties deepSeekProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final RagService ragService;

    private final int topKPerQuery;
    private final int materialCap;
    private final int maxTokens;

    public PlannerService(
            DeepSeekProperties deepSeekProperties,
            ObjectMapper objectMapper,
            RagService ragService,
            @Value("${knowledge.planner.top-k-per-query:8}") int topKPerQuery,
            @Value("${knowledge.planner.material-cap:24}") int materialCap,
            @Value("${knowledge.planner.max-tokens:2800}") int maxTokens
    ) {
        this.deepSeekProperties = deepSeekProperties;
        this.objectMapper = objectMapper;
        this.ragService = ragService;
        this.topKPerQuery = topKPerQuery;
        this.materialCap = materialCap;
        this.maxTokens = maxTokens;
        this.restClient = RestClient.builder()
                .baseUrl(deepSeekProperties.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public PlanDraftResponse buildDraft(PlanDraftRequest request) {
        return buildDraft(
                request.userId(),
                request.destination(),
                request.days(),
                request.notes(),
                request.topKPerQuery()
        );
    }

    public PlanDraftResponse buildDraft(String userId, String destination, int days, String notes, Integer topKOverride) {
        if (!StringUtils.hasText(deepSeekProperties.apiKey())) {
            throw new IllegalStateException("未配置 DEEPSEEK_API_KEY");
        }
        String dest = destination.trim();
        int k = topKOverride != null ? topKOverride : topKPerQuery;

        List<String> queries = List.of(
                dest + " 景点 攻略 必去 推荐",
                dest + " 美食 餐厅 小吃",
                dest + " 交通 住宿 区域 移动"
        );

        Map<String, RagCitation> merged = new LinkedHashMap<>();
        for (String q : queries) {
            for (RagCitation c : ragService.retrieveCitations(q, k)) {
                merged.merge(c.id(), c, PlannerService::preferCloser);
            }
        }

        List<RagCitation> materials = merged.values().stream()
                .sorted(Comparator.comparing(PlannerService::distanceOrMax))
                .limit(materialCap)
                .toList();

        String contextBlock = buildContextBlock(materials);
        String userBlock = buildUserBlock(dest, days, notes, contextBlock);
        String draft = callPlannerModel(userBlock);

        return new PlanDraftResponse(
                UUID.randomUUID().toString(),
                userId,
                dest,
                days,
                draft,
                materials,
                Instant.now()
        );
    }

    private static RagCitation preferCloser(RagCitation a, RagCitation b) {
        double da = distanceOrMax(a);
        double db = distanceOrMax(b);
        return da <= db ? a : b;
    }

    private static double distanceOrMax(RagCitation c) {
        if (c.distance() == null) {
            return Double.MAX_VALUE;
        }
        return c.distance();
    }

    private static String buildContextBlock(List<RagCitation> citations) {
        if (citations.isEmpty()) {
            return "（当前知识库未检索到与目的地足够相似的条目。请确认已导入该地区攻略或稍后重试。）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < citations.size(); i++) {
            RagCitation c = citations.get(i);
            sb.append("--- 素材 ").append(i + 1).append(" ---\n");
            sb.append(c.snippet()).append("\n\n");
        }
        return sb.toString().trim();
    }

    private static String buildUserBlock(String destination, int days, String notes, String contextBlock) {
        StringBuilder ub = new StringBuilder();
        ub.append("目的地：").append(destination).append("\n");
        ub.append("行程天数：").append(days).append("\n");
        if (StringUtils.hasText(notes)) {
            ub.append("用户备注/调整要求：\n").append(notes.trim()).append("\n");
        }
        ub.append("\n知识库素材：\n").append(contextBlock);
        return ub.toString();
    }

    private String callPlannerModel(String userContent) {
        try {
            Map<String, Object> payload = Map.of(
                    "model", deepSeekProperties.model(),
                    "temperature", 0.35,
                    "max_tokens", maxTokens,
                    "messages", List.of(
                            Map.of("role", "system", "content", PLANNER_SYSTEM_PROMPT),
                            Map.of("role", "user", "content", userContent)
                    )
            );
            String rawResponse = restClient.post()
                    .uri(deepSeekProperties.chatPath())
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + deepSeekProperties.apiKey())
                    .body(payload)
                    .retrieve()
                    .body(String.class);
            JsonNode root = objectMapper.readTree(rawResponse);
            return root.path("choices").path(0).path("message").path("content").asText("").trim();
        } catch (Exception ex) {
            log.warn("规划师模型调用失败", ex);
            return "行程草案生成失败，请稍后重试。若向量库中尚无该地区素材，请先导入攻略再规划。";
        }
    }
}
