package com.travelmaster.api.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travelmaster.api.config.DeepSeekProperties;
import com.travelmaster.api.dto.KnowledgeIngestResponse;
import jakarta.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Service
public class KnowledgeIngestService {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIngestService.class);

    private final DeepSeekProperties deepSeekProperties;
    private final ObjectMapper objectMapper;

    private final long maxFileSizeBytes;
    private final Duration pythonTimeout;
    private final String vectorDbPath;
    private final String collection;
    private final String embeddingModel;
    private final String deepseekVisionModel;
    private final int maxPages;
    private final int pdfDpi;

    private final Object pythonDepsLock = new Object();
    private volatile boolean pythonDepsEnsured = false;
    private final String pythonVenvPath;

    public KnowledgeIngestService(
            DeepSeekProperties deepSeekProperties,
            ObjectMapper objectMapper,
            @Value("${knowledge.ingest.max-file-size-mb:20}") long maxFileSizeMb,
            @Value("${knowledge.ingest.python-timeout-seconds:180}") long pythonTimeoutSeconds,
            @Value("${knowledge.ingest.vector-db-path:../data/vector_db/chroma}") String vectorDbPath,
            @Value("${knowledge.ingest.collection:travel_master_knowledge}") String collection,
            @Value("${knowledge.ingest.embedding-model:sentence-transformers/all-MiniLM-L6-v2}") String embeddingModel,
            @Value("${knowledge.ingest.deepseek-vision-model:}") String deepseekVisionModel,
            @Value("${knowledge.ingest.max-pages:10}") int maxPages,
            @Value("${knowledge.ingest.pdf-dpi:300}") int pdfDpi,
            @Value("${knowledge.ingest.python-venv-path:../data/python_venv}") String pythonVenvPath
    ) {
        this.deepSeekProperties = deepSeekProperties;
        this.objectMapper = objectMapper;
        this.maxFileSizeBytes = maxFileSizeMb * 1024L * 1024L;
        this.pythonTimeout = Duration.ofSeconds(pythonTimeoutSeconds);
        this.vectorDbPath = vectorDbPath;
        this.collection = collection;
        this.embeddingModel = embeddingModel;
        this.deepseekVisionModel = deepseekVisionModel;
        this.maxPages = maxPages;
        this.pdfDpi = pdfDpi;
        this.pythonVenvPath = pythonVenvPath;
    }

    /**
     * 供 RAG 等模块复用：确保 venv 与 chromadb 等 Python 依赖可用。
     */
    public void preparePythonRuntime() {
        ensurePythonDependenciesIfNeeded();
    }

    /**
     * 供 RAG 等模块调用 Python 脚本（chromadb 检索等）。
     */
    public String resolvePythonInterpreter() {
        ensurePythonDependenciesIfNeeded();
        return getPythonExecutable();
    }

    public KnowledgeIngestResponse ingest(
            String userId,
            @Nullable String sourceType,
            @Nullable String title,
            @Nullable MultipartFile file,
            @Nullable String url
    ) {
        boolean hasFile = file != null && !file.isEmpty();
        boolean hasUrl = url != null && !url.trim().isEmpty();
        String urlValue = hasUrl ? url.trim() : "";

        if (!hasFile && !hasUrl) {
            throw new IllegalArgumentException("需要提供 file 或 url");
        }
        if (hasFile && hasUrl) {
            throw new IllegalArgumentException("file 与 url 只能二选一");
        }

        if (hasFile) {
            if (file.getSize() > maxFileSizeBytes) {
                throw new IllegalArgumentException("文件过大，最大允许 " + (maxFileSizeBytes / 1024 / 1024) + "MB");
            }
            if (file.getOriginalFilename() == null || file.getOriginalFilename().trim().isEmpty()) {
                throw new IllegalArgumentException("file.originalFilename 不能为空");
            }
        }

        if (!org.springframework.util.StringUtils.hasText(deepSeekProperties.apiKey())) {
            throw new IllegalStateException("未配置 DEEPSEEK_API_KEY");
        }

        ensurePythonDependenciesIfNeeded();

        String requestId = UUID.randomUUID().toString();

        Path uploadedTemp = null;
        Path scriptTemp = null;

        try {
            if (hasFile) {
                uploadedTemp = saveMultipartToTempFile(file);
            }
            scriptTemp = extractPythonScriptTemp();

            List<String> cmd = new ArrayList<>();
            cmd.add(getPythonExecutable());
            cmd.add(scriptTemp.toAbsolutePath().toString());
            if (hasFile) {
                cmd.add("--file");
                cmd.add(uploadedTemp.toAbsolutePath().toString());
            } else if (hasUrl) {
                cmd.add("--url");
                cmd.add(urlValue);
            }
            cmd.add("--user-id");
            cmd.add(userId);
            cmd.add("--source-type");
            cmd.add(sourceType == null ? "" : sourceType);
            cmd.add("--title");
            cmd.add(title == null ? "" : title);
            cmd.add("--vector-db-path");
            cmd.add(vectorDbPath);
            cmd.add("--collection");
            cmd.add(collection);
            cmd.add("--embedding-model");
            cmd.add(embeddingModel);
            cmd.add("--max-pages");
            cmd.add(String.valueOf(maxPages));
            cmd.add("--pdf-dpi");
            cmd.add(String.valueOf(pdfDpi));
            cmd.add("--deepseek-base-url");
            cmd.add(deepSeekProperties.baseUrl());
            cmd.add("--deepseek-chat-path");
            cmd.add(deepSeekProperties.chatPath());
            cmd.add("--deepseek-model");
            cmd.add(deepSeekProperties.model());
            cmd.add("--deepseek-vision-model");
            cmd.add(deepseekVisionModel == null ? "" : deepseekVisionModel);
            cmd.add("--deepseek-api-key");
            cmd.add(deepSeekProperties.apiKey());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(new java.io.File(System.getProperty("user.dir")));
            pb.redirectErrorStream(false);

            long start = System.currentTimeMillis();
            Process process = pb.start();
            boolean finished;
            try {
                finished = process.waitFor(pythonTimeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
                throw new RuntimeException("知识入库被中断", ie);
            }
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("知识入库超时，已中止");
            }

            String stdout = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

            if (process.exitValue() != 0) {
                log.warn("知识入库失败：exit={}, stdout={}, stderr={}", process.exitValue(), trimOneLine(stdout), trimOneLine(stderr));
                throw new RuntimeException("知识入库失败: " + (stderr.isBlank() ? stdout : stderr));
            }

            JsonNode root = objectMapper.readTree(stdout);
            boolean ok = root.path("ok").asBoolean(false);
            if (!ok) {
                String err = root.path("error").asText("未知错误");
                throw new RuntimeException("知识入库失败: " + err);
            }

            String docId = root.path("doc_id").asText();
            String fileName = root.path("file_name").asText();
            int chunksAdded = root.path("chunks_added").asInt();
            String vectorDbPathOut = root.path("vector_db_path").asText();
            String collectionOut = root.path("collection").asText();
            String docSummary = root.path("doc_summary").asText();

            List<String> docKeywords = new ArrayList<>();
            for (JsonNode kw : root.path("doc_keywords")) {
                docKeywords.add(kw.asText());
            }

            List<KnowledgeIngestResponse.Page> pages = new ArrayList<>();
            for (JsonNode p : root.path("pages")) {
                pages.add(new KnowledgeIngestResponse.Page(
                        p.path("page_index").asInt(),
                        p.path("method").asText(),
                        p.path("text_len").asInt()
                ));
            }

            log.info("知识入库完成: requestId={}, docId={}, chunks={}, costMs={}",
                    requestId, docId, chunksAdded, System.currentTimeMillis() - start);

            return new KnowledgeIngestResponse(
                    requestId,
                    userId,
                    fileName,
                    docId,
                    chunksAdded,
                    vectorDbPathOut,
                    collectionOut,
                    docSummary,
                    docKeywords,
                    pages,
                    true
            );
        } catch (IOException e) {
            throw new RuntimeException("处理文件/脚本失败: " + e.getMessage(), e);
        } finally {
            safeDelete(uploadedTemp);
            safeDelete(scriptTemp);
        }
    }

    private void ensurePythonDependenciesIfNeeded() {
        if (pythonDepsEnsured) {
            return;
        }
        synchronized (pythonDepsLock) {
            if (pythonDepsEnsured) {
                return;
            }
            PythonCheckResult check = pythonCheckModules(getPythonExecutableForCheck());
            if (check.ok) {
                pythonDepsEnsured = true;
                return;
            }

            Path requirementsPath = resolveRequirementsTxtPath();
            if (requirementsPath == null) {
                throw new RuntimeException("Python 依赖未就绪，且未找到 requirements.txt（需要你手动安装 chromadb 等依赖）：" + check.stderr);
            }

            log.warn("Python 依赖缺失，尝试在 venv 自动安装: {}", requirementsPath);
            PythonInstallResult install = pythonPipInstallInVenv(requirementsPath);
            if (!install.ok) {
                throw new RuntimeException("自动安装 Python 依赖失败，pip 输出: " + install.stderr);
            }

            PythonCheckResult after = pythonCheckModules(getPythonExecutableForCheck());
            if (!after.ok) {
                throw new RuntimeException("自动安装后仍无法 import Python 依赖，请检查你的 Python 环境。last stderr: " + after.stderr);
            }

            pythonDepsEnsured = true;
        }
    }

    private record PythonCheckResult(boolean ok, String stderr) {
    }

    private PythonCheckResult pythonCheckModules(String pythonExecutable) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    pythonExecutable,
                    "-c",
                    "import chromadb, bs4; import PIL; print('ok')"
            );
            pb.redirectErrorStream(false);
            Process p = pb.start();
            int exit = p.waitFor(15, java.util.concurrent.TimeUnit.SECONDS) ? p.exitValue() : -1;
            String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (exit == 0) {
                return new PythonCheckResult(true, "");
            }
            return new PythonCheckResult(false, trimOneLine(stderr));
        } catch (Exception e) {
            return new PythonCheckResult(false, trimOneLine(Objects.toString(e.getMessage(), "")));
        }
    }

    private record PythonInstallResult(boolean ok, String stderr) {
    }

    private Path resolveRequirementsTxtPath() {
        Path cwd = Path.of(System.getProperty("user.dir"));
        Path[] candidates = new Path[]{
                cwd.resolve("requirements.txt"),
                cwd.getParent() == null ? null : cwd.getParent().resolve("requirements.txt"),
                cwd.getParent() == null || cwd.getParent().getParent() == null ? null : cwd.getParent().getParent().resolve("requirements.txt"),
        };
        for (Path c : candidates) {
            if (c != null && Files.exists(c)) {
                return c;
            }
        }
        return null;
    }

    private PythonInstallResult pythonPipInstallInVenv(Path requirementsPath) {
        try {
            ensureVenvExists();
            String venvPython = getVenvPythonExecutable();
            ProcessBuilder pb = new ProcessBuilder(
                    venvPython,
                    "-m",
                    "pip",
                    "install",
                    "-r",
                    requirementsPath.toAbsolutePath().toString(),
                    "--no-cache-dir"
            );
            pb.redirectErrorStream(false);
            pb.directory(requirementsPath.getParent().toFile());
            Process p = pb.start();
            boolean finished = p.waitFor(10, java.util.concurrent.TimeUnit.MINUTES);
            String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!finished) {
                p.destroyForcibly();
                return new PythonInstallResult(false, "pip install 超时: " + trimOneLine(stderr));
            }
            if (p.exitValue() != 0) {
                return new PythonInstallResult(false, trimOneLine(stderr));
            }
            return new PythonInstallResult(true, "");
        } catch (Exception e) {
            return new PythonInstallResult(false, trimOneLine(Objects.toString(e.getMessage(), "")));
        }
    }

    private void ensureVenvExists() throws IOException, InterruptedException {
        Path venvDir = Path.of(pythonVenvPath);
        Path venvPython = getVenvPythonPath(venvDir);
        if (Files.exists(venvPython)) {
            return;
        }

        ProcessBuilder pb = new ProcessBuilder("python3", "-m", "venv", venvDir.toAbsolutePath().toString());
        pb.redirectErrorStream(false);
        Process p = pb.start();
        boolean finished = p.waitFor(5, java.util.concurrent.TimeUnit.MINUTES);
        if (!finished || p.exitValue() != 0) {
            String stderr = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            throw new RuntimeException("创建 python venv 失败: " + trimOneLine(stderr));
        }
    }

    private Path getVenvPythonPath(Path venvDir) {
        return venvDir.resolve("bin/python");
    }

    private String getVenvPythonExecutable() {
        return getVenvPythonPath(Path.of(pythonVenvPath)).toAbsolutePath().toString();
    }

    private String getPythonExecutableForCheck() {
        Path venvPython = getVenvPythonPath(Path.of(pythonVenvPath));
        if (Files.exists(venvPython)) {
            return venvPython.toAbsolutePath().toString();
        }
        return "python3";
    }

    private String getPythonExecutable() {
        return getVenvPythonExecutable();
    }

    private Path saveMultipartToTempFile(MultipartFile file) {
        String original = file.getOriginalFilename();
        String suffix = "";
        if (original != null) {
            int dot = original.lastIndexOf('.');
            if (dot >= 0 && dot < original.length() - 1) {
                suffix = original.substring(dot);
            }
        }
        try {
            Path temp = Files.createTempFile("travelmaster_upload_", suffix);
            file.transferTo(temp);
            return temp;
        } catch (IOException e) {
            throw new RuntimeException("保存上传文件失败: " + e.getMessage(), e);
        }
    }

    private Path extractPythonScriptTemp() {
        try (InputStream in = new ClassPathResource("python/knowledge_ingest.py").getInputStream()) {
            Path temp = Files.createTempFile("travelmaster_knowledge_ingest_", ".py");
            Files.copy(in, temp, StandardCopyOption.REPLACE_EXISTING);
            return temp;
        } catch (IOException e) {
            throw new RuntimeException("提取 Python 脚本失败: " + e.getMessage(), e);
        }
    }

    private static void safeDelete(@Nullable Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private static String trimOneLine(String s) {
        if (s == null) {
            return "";
        }
        return s.replace('\n', ' ').replace('\r', ' ').trim();
    }
}
