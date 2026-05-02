package com.travelmaster.api.controller;

import com.travelmaster.api.dto.RagQueryRequest;
import com.travelmaster.api.dto.RagQueryResponse;
import com.travelmaster.api.service.RagService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class RagController {

    private final RagService ragService;

    public RagController(RagService ragService) {
        this.ragService = ragService;
    }

    @PostMapping("/rag/query")
    public ResponseEntity<RagQueryResponse> query(@Valid @RequestBody RagQueryRequest request) {
        return ResponseEntity.ok(ragService.answer(request));
    }
}
