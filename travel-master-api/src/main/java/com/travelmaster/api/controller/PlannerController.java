package com.travelmaster.api.controller;

import com.travelmaster.api.dto.PlanDraftRequest;
import com.travelmaster.api.dto.PlanDraftResponse;
import com.travelmaster.api.service.PlannerService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/planner")
public class PlannerController {

    private final PlannerService plannerService;

    public PlannerController(PlannerService plannerService) {
        this.plannerService = plannerService;
    }

    /**
     * 根据目的地与天数从向量库取素材并生成行程草案（不经过对话调度）。
     */
    @PostMapping("/draft")
    public ResponseEntity<PlanDraftResponse> draft(@Valid @RequestBody PlanDraftRequest request) {
        return ResponseEntity.ok(plannerService.buildDraft(request));
    }
}
