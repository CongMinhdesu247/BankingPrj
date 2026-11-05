package com.minh.banking.auth.controller;

import com.minh.banking.auth.dto.RegisterRequest;
import com.minh.banking.auth.service.RegistrationService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth/register")
public class RegistrationController {
    private final RegistrationService registrations;
    public RegistrationController(RegistrationService registrations) { this.registrations=registrations; }
    @PostMapping
    public ResponseEntity<RegistrationService.Result> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(201).header("Cache-Control","no-store").header("Pragma","no-cache")
            .body(registrations.register(request));
    }
}
