package com.travelmaster.api.controller;

import com.travelmaster.api.dto.KnowledgeIngestResponse;
import com.travelmaster.api.service.KnowledgeIngestService;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class KnowledgeIngestController {

    private final KnowledgeIngestService knowledgeIngestService;

    public KnowledgeIngestController(KnowledgeIngestService knowledgeIngestService) {
        this.knowledgeIngestService = knowledgeIngestService;
    }

    @PostMapping(
            value = "/knowledge/ingest",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE
    )
    public ResponseEntity<KnowledgeIngestResponse> ingest(
            @RequestParam("userId") @NotBlank String userId,
            @RequestParam(value = "sourceType", required = false) String sourceType,
            @RequestParam(value = "title", required = false) String title,
            @RequestParam(value = "url", required = false) String url,
            @RequestParam(value = "file", required = false) MultipartFile file
    ) {
        return ResponseEntity.ok(knowledgeIngestService.ingest(userId, sourceType, title, file, url));
    }

    @PostMapping("/knowledge/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}

