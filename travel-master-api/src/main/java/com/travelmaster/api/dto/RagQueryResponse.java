package com.travelmaster.api.dto;

import java.time.Instant;
import java.util.List;

public record RagQueryResponse(
        String requestId,
        String userId,
        String query,
        String answer,
        List<RagCitation> citations,
        Instant answeredAt
) {
}
