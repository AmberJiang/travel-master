package com.travelmaster.api.controller;

import com.travelmaster.api.dto.DispatchRequest;
import com.travelmaster.api.dto.DispatchResponse;
import com.travelmaster.api.service.DispatcherService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
public class DispatcherController {

    private final DispatcherService dispatcherService;

    public DispatcherController(DispatcherService dispatcherService) {
        this.dispatcherService = dispatcherService;
    }

    @PostMapping("/dispatcher")
    public ResponseEntity<DispatchResponse> dispatch(@Valid @RequestBody DispatchRequest request) {
        return ResponseEntity.ok(dispatcherService.dispatch(request));
    }
}
