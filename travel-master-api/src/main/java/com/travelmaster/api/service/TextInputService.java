package com.travelmaster.api.service;

import com.travelmaster.api.dto.UserTextInputRequest;
import com.travelmaster.api.dto.UserTextInputResponse;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class TextInputService {

    public UserTextInputResponse process(UserTextInputRequest request) {
        String normalized = request.message().trim().replaceAll("\\s+", " ");
        return new UserTextInputResponse(
                UUID.randomUUID().toString(),
                request.userId(),
                request.message(),
                normalized,
                normalized.length(),
                Instant.now()
        );
    }
}
