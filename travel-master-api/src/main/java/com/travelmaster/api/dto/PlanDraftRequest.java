package com.travelmaster.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 路线规划师输入：目的地与天数为必填；备注用于约束（亲子、少走路等）。
 */
public record PlanDraftRequest(
        @NotBlank @Size(max = 128) String userId,
        @NotBlank @Size(max = 128) String destination,
        @Min(1) @Max(30) int days,
        @Size(max = 2000) String notes,
        /** 覆盖默认的每路检索条数，可为 null */
        @Min(1) @Max(20) Integer topKPerQuery
) {
}
