package com.travelmaster.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record DispatchRequest(
        @NotBlank(message = "userId 不能为空")
        String userId,
        @NotBlank(message = "message 不能为空")
        @Size(max = 5000, message = "message 长度不能超过 5000")
        String message
) {
}
