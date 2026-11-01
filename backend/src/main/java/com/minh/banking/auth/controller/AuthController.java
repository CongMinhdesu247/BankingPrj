package com.minh.banking.auth.controller;

import com.minh.banking.auth.dto.*;
import com.minh.banking.auth.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/auth")
public class AuthController {
    private final AuthService auth;
    public AuthController(AuthService auth) { this.auth = auth; }
    @PostMapping("/login")
    public ResponseEntity<TokenResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").header("Pragma", "no-cache").body(auth.login(request));
    }
}
