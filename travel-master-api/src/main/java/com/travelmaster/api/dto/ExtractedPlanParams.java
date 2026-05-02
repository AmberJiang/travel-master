package com.travelmaster.api.dto;

/**
 * 从自然语言中抽取的规划参数（可能不完整）。
 */
public record ExtractedPlanParams(
        String destination,
        Integer days,
        String adjustmentHint
) {
    public boolean readyForDraft() {
        return destination != null
                && !destination.isBlank()
                && days != null
                && days >= 1;
    }
}
