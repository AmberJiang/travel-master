package com.travelmaster.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * 行程草案与用于生成的知识库素材引用。
 */
public record PlanDraftResponse(
        String requestId,
        String userId,
        String destination,
        int days,
        String itineraryDraft,
        List<RagCitation> materials,
        Instant generatedAt
) {
}
