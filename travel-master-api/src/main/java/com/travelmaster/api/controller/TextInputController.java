package com.travelmaster.api.controller;

import com.travelmaster.api.dto.UserTextInputRequest;
import com.travelmaster.api.dto.UserTextInputResponse;
import com.travelmaster.api.service.TextInputService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1")
public class TextInputController {

    private final TextInputService textInputService;

    public TextInputController(TextInputService textInputService) {
        this.textInputService = textInputService;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }

    @PostMapping("/user-input/text")
    public ResponseEntity<UserTextInputResponse> receiveTextInput(@Valid @RequestBody UserTextInputRequest request) {
        return ResponseEntity.ok(textInputService.process(request));
    }
}
