package com.travelmaster.api.service;

import com.travelmaster.api.dto.DispatchRequest;
import com.travelmaster.api.dto.DispatchResponse;
import com.travelmaster.api.dto.ExtractedPlanParams;
import com.travelmaster.api.dto.IntentType;
import com.travelmaster.api.dto.PlanDraftResponse;
import com.travelmaster.api.dto.RagCitation;
import com.travelmaster.api.dto.RagQueryRequest;
import com.travelmaster.api.dto.RagQueryResponse;
import com.travelmaster.api.dto.UserTextInputRequest;
import com.travelmaster.api.dto.UserTextInputResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class TextInputService {

    private static final Logger log = LoggerFactory.getLogger(TextInputService.class);

    private final DispatcherService dispatcherService;
    private final RagService ragService;
    private final PlannerService plannerService;
    private final PlanParameterExtractorService planParameterExtractorService;

    public TextInputService(
            DispatcherService dispatcherService,
            RagService ragService,
            PlannerService plannerService,
            PlanParameterExtractorService planParameterExtractorService
    ) {
        this.dispatcherService = dispatcherService;
        this.ragService = ragService;
        this.plannerService = plannerService;
        this.planParameterExtractorService = planParameterExtractorService;
    }

    public UserTextInputResponse process(UserTextInputRequest request) {
        DispatchResponse dispatch = dispatcherService.dispatch(
                new DispatchRequest(request.userId(), request.message()));

        String ragAnswer = null;
        List<RagCitation> ragCitations = null;

        if (dispatch.intent() == IntentType.QUESTION) {
            try {
                RagQueryResponse rag = ragService.answer(
                        new RagQueryRequest(request.userId(), dispatch.normalizedMessage(), null));
                ragAnswer = rag.answer();
                ragCitations = rag.citations();
            } catch (Exception ex) {
                log.warn("QUESTION 意图下 RAG 失败", ex);
                ragAnswer = "知识库检索或生成失败，请稍后重试。";
                ragCitations = List.of();
            }
        } else if (dispatch.intent() == IntentType.PLAN_ITINERARY
                || dispatch.intent() == IntentType.REPLAN_ROUTE) {
            try {
                ExtractedPlanParams extracted = planParameterExtractorService.extract(dispatch.normalizedMessage());
                if (extracted.readyForDraft()) {
                    int days = Math.min(30, Math.max(1, extracted.days()));
                    String notes = buildPlannerNotes(dispatch.intent(), dispatch.normalizedMessage(), extracted);
                    PlanDraftResponse plan = plannerService.buildDraft(
                            request.userId(),
                            extracted.destination(),
                            days,
                            notes,
                            null
                    );
                    ragAnswer = plan.itineraryDraft();
                    ragCitations = plan.materials();
                } else if (dispatch.intent() == IntentType.PLAN_ITINERARY) {
                    ragAnswer = "请补充目的地与行程天数，例如：「冲绳5天怎么安排」。";
                    ragCitations = List.of();
                } else {
                    ragAnswer = "请说明目的地、行程天数以及要如何调整（例如：「冲绳4天，把北部景点集中到一天」）。";
                    ragCitations = List.of();
                }
            } catch (Exception ex) {
                log.warn("规划师链路失败", ex);
                ragAnswer = "行程规划失败，请稍后重试。";
                ragCitations = List.of();
            }
        }

        return new UserTextInputResponse(
                UUID.randomUUID().toString(),
                request.userId(),
                request.message(),
                dispatch.normalizedMessage(),
                dispatch.normalizedMessage().length(),
                Instant.now(),
                dispatch.intent(),
                dispatch.reasoning(),
                dispatch.routeToAgent(),
                ragAnswer,
                ragCitations
        );
    }

    private static String buildPlannerNotes(
            IntentType intent,
            String normalizedMessage,
            ExtractedPlanParams extracted
    ) {
        StringBuilder sb = new StringBuilder();
        if (intent == IntentType.REPLAN_ROUTE && StringUtils.hasText(extracted.adjustmentHint())) {
            sb.append("调整要求：").append(extracted.adjustmentHint().trim()).append("\n");
        }
        sb.append("用户原话：").append(normalizedMessage.trim());
        return sb.toString();
    }
}
