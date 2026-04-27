package com.travelmaster.api.dto;

import java.time.Instant;

public record DispatchResponse(
        String requestId,
        String userId,
        String normalizedMessage,
        IntentType intent,
        String reasoning,
        String routeToAgent,
        Instant dispatchedAt
) {
}
