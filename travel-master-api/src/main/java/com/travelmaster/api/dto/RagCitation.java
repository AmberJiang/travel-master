package com.travelmaster.api.dto;

import com.fasterxml.jackson.databind.JsonNode;

public record RagCitation(
        String id,
        String snippet,
        JsonNode metadata,
        Double distance
) {
}
