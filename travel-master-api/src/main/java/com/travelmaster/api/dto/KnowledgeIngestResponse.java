package com.travelmaster.api.dto;

import java.util.List;

public record KnowledgeIngestResponse(
        String requestId,
        String userId,
        String fileName,
        String docId,
        int chunksAdded,
        String vectorDbPath,
        String collection,
        String docSummary,
        List<String> docKeywords,
        List<Page> pages,
        boolean ok
) {
    public record Page(
            int pageIndex,
            String method,
            int textLen
    ) {
    }
}

