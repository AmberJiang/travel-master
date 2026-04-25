package com.travelmaster.api.dto;

import java.time.Instant;

public record UserTextInputResponse(
        String requestId,
        String userId,
        String originalMessage,
        String normalizedMessage,
        int messageLength,
        Instant receivedAt
) {
}
