package com.travelmaster.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RagQueryRequest(
        @NotBlank(message = "userId 不能为空")
        String userId,
        @NotBlank(message = "query 不能为空")
        @Size(max = 2000, message = "query 长度不能超过 2000")
        String query,
        @Min(1)
        @Max(20)
        Integer topK
) {
}
