package com.travelmaster.api.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.deepseek")
public record DeepSeekProperties(
        String baseUrl,
        String chatPath,
        String model,
        String apiKey
) {
}
