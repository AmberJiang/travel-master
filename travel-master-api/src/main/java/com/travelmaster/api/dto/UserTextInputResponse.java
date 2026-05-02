package com.travelmaster.api.dto;

import java.time.Instant;
import java.util.List;

public record UserTextInputResponse(
        String requestId,
        String userId,
        String originalMessage,
        String normalizedMessage,
        int messageLength,
        Instant receivedAt,
        IntentType intent,
        String reasoning,
        String routeToAgent,
        String ragAnswer,
        List<RagCitation> ragCitations
) {
}
