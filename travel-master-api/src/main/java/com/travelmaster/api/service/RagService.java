package com.travelmaster.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmaster.api.config.DeepSeekProperties;
import com.travelmaster.api.dto.RagCitation;
import com.travelmaster.api.dto.RagQueryRequest;
import com.travelmaster.api.dto.RagQueryResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private static final String RAG_SYSTEM_PROMPT = """
            你是 Travel Master 的旅行知识助手，使用「检索增强生成（RAG）」回答用户问题。
            规则：
            1) 只能依据用户消息后面附带的「知识库片段」作答；不要编造片段中不存在的事实（票价、地址、时间等以片段为准）。
            2) 若片段不足以回答，请明确说「知识库中没有相关信息」，并简要说明缺什么信息；不要假装知道。
            3) 回答使用简体中文，条理清晰；如有多条景点相关，可分点列出。
            4) 不要输出 JSON，不要使用 markdown 代码块包裹全文。
            """;

    private final DeepSeekProperties deepSeekProperties;
    private final ObjectMapper objectMapper;
    private final RestClient restClient;
    private final KnowledgeIngestService knowledgeIngestService;

    private final String vectorDbPath;
    private final String collection;
    private final int defaultTopK;
    private final Duration retrieveTimeout;

    public RagService(
            DeepSeekProperties deepSeekProperties,
            ObjectMapper objectMapper,
            KnowledgeIngestService knowledgeIngestService,
            @Value("${knowledge.ingest.vector-db-path:../data/vector_db/chroma}") String vectorDbPath,
            @Value("${knowledge.ingest.collection:travel_master_knowledge}") String collection,
            @Value("${knowledge.rag.top-k:5}") int defaultTopK,
            @Value("${knowledge.rag.python-timeout-seconds:45}") long retrieveTimeoutSeconds
    ) {
        this.deepSeekProperties = deepSeekProperties;
        this.objectMapper = objectMapper;
        this.knowledgeIngestService = knowledgeIngestService;
        this.vectorDbPath = vectorDbPath;
        this.collection = collection;
        this.defaultTopK = defaultTopK;
        this.retrieveTimeout = Duration.ofSeconds(retrieveTimeoutSeconds);
        this.restClient = RestClient.builder()
                .baseUrl(deepSeekProperties.baseUrl())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public RagQueryResponse answer(RagQueryRequest request) {
        if (!StringUtils.hasText(deepSeekProperties.apiKey())) {
            throw new IllegalStateException("未配置 DEEPSEEK_API_KEY");
        }

        String requestId = UUID.randomUUID().toString();
        String query = request.query().trim();
        int topK = request.topK() != null ? request.topK() : defaultTopK;

        knowledgeIngestService.preparePythonRuntime();

        List<RagCitation> citations = retrieveFromChroma(query, topK);
        String contextBlock = buildContextBlock(citations);

        String userContent = "用户问题：\n" + query + "\n\n知识库片段：\n" + contextBlock;

        String answer = callDeepSeekChat(userContent);

        return new RagQueryResponse(
                requestId,
                request.userId(),
                query,
                answer,
                citations,
                Instant.now()
        );
    }

    /**
     * 仅从向量库检索片段，不调用大模型生成回答。供路线规划师等多路检索合并素材使用。
     */
    public List<RagCitation> retrieveCitations(String query, Integer topK) {
        if (!StringUtils.hasText(query)) {
            return List.of();
        }
        int k = topK != null ? topK : defaultTopK;
        knowledgeIngestService.preparePythonRuntime();
        return retrieveFromChroma(query.trim(), k);
    }

    private List<RagCitation> retrieveFromChroma(String query, int topK) {
        Path scriptTemp = null;
        try {
            scriptTemp = extractRetrieveScriptTemp();
            Path dbAbs = Path.of(vectorDbPath).toAbsolutePath().normalize();

            List<String> cmd = new ArrayList<>();
            cmd.add(knowledgeIngestService.resolvePythonInterpreter());
            cmd.add(scriptTemp.toAbsolutePath().toString());
            cmd.add("--query");
            cmd.add(query);
            cmd.add("--vector-db-path");
            cmd.add(dbAbs.toString());
            cmd.add("--collection");
            cmd.add(collection);
            cmd.add("--n-results");
            cmd.add(String.valueOf(topK));

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new java.io.File(System.getProperty("user.dir")));
            pb.redirectErrorStream(false);

            long start = System.currentTimeMillis();
            Process process = pb.start();
            boolean finished;
            try {
                finished = process.waitFor(retrieveTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new RuntimeException("向量检索被中断", ie);
            }
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("向量检索超时");
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

            if (process.exitValue() != 0) {
                log.warn("Chroma 检索失败：exit={}, stderr={}", process.exitValue(), trimOneLine(stderr));
                throw new RuntimeException("向量检索失败: " + (stderr.isBlank() ? stdout : stderr));
            }

            JsonNode root = objectMapper.readTree(stdout);
            if (!root.path("ok").asBoolean(false)) {
                throw new RuntimeException("向量检索失败: " + root.path("error").asText("未知错误"));
            }

            List<RagCitation> citations = new ArrayList<>();
            for (JsonNode h : root.path("hits")) {
                String id = h.path("id").asText("");
                String doc = h.path("document").asText("");
                JsonNode meta = h.path("metadata");
                if (meta.isMissingNode() || meta.isNull()) {
                    meta = objectMapper.createObjectNode();
                }
                Double distance = null;
                if (h.hasNonNull("distance")) {
                    distance = h.get("distance").asDouble();
                }
                citations.add(new RagCitation(id, doc, meta, distance));
            }

            log.debug("Chroma 检索完成: hits={}, costMs={}", citations.size(), System.currentTimeMillis() - start);
            return citations;
        } catch (IOException e) {
            throw new RuntimeException("向量检索 IO 失败: " + e.getMessage(), e);
        } catch (Exception e) {
            throw new RuntimeException("向量检索失败: " + e.getMessage(), e);
        } finally {
            safeDelete(scriptTemp);
        }
    }

    private String buildContextBlock(List<RagCitation> citations) {
        if (citations.isEmpty()) {
            return "（无检索结果：知识库中未找到与问题足够相似的条目。）";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < citations.size(); i++) {
            RagCitation c = citations.get(i);
            sb.append("--- 片段 ").append(i + 1).append(" ---\n");
            sb.append(c.snippet()).append("\n\n");
        }
        return sb.toString().trim();
    }

    private String callDeepSeekChat(String userContent) {
        try {
            Map<String, Object> payload = Map.of(
                    "model", deepSeekProperties.model(),
                    "temperature", 0.2,
                    "max_tokens", 1200,
                    "messages", List.of(
                            Map.of("role", "system", "content", RAG_SYSTEM_PROMPT),
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
            log.warn("DeepSeek RAG 调用失败", ex);
            return "模型调用失败，暂时无法生成回答。请稍后重试。";
        }
    }

    private Path extractRetrieveScriptTemp() throws IOException {
        try (InputStream in = new ClassPathResource("python/chroma_retrieve.py").getInputStream()) {
            Path temp = Files.createTempFile("travelmaster_chroma_retrieve_", ".py");
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            return temp;
        }
    }

    private static void safeDelete(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static String trimOneLine(String s) {
        return Objects.toString(s, "").replace('\n', ' ').replace('\r', ' ').trim();
    }
}
