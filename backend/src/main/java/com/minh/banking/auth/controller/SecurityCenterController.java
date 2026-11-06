package com.minh.banking.auth.controller;
import com.minh.banking.auth.service.SecurityCenterService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
@RestController @RequestMapping("/api/v1/me/security") @PreAuthorize("isAuthenticated()")
public class SecurityCenterController {
    private final SecurityCenterService service;
    public SecurityCenterController(SecurityCenterService service) {
        this.service = service;
    }
    @GetMapping public Map<String, Object> settings() {
        return service.settings();
    }
    @PostMapping("/logout") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void logout() {
        service.revoke(service.sessionId());
    }
    @GetMapping("/sessions") public List<SecurityCenterService.Session> sessions() {
        return service.sessions();
    }
    @DeleteMapping("/sessions/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID id) {
        service.revoke(id);
    }
    @DeleteMapping("/sessions") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void others() {
        service.revokeOthers();
    }
    @PostMapping("/password") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void password(@Valid @RequestBody SecurityCenterService.PasswordChange request) {
        service.changePassword(request);
    }
    @PostMapping("/totp/setup") public Map<String, String> setup(@Valid @RequestBody SecurityCenterService.Proof request) {
        return service.setup(request);
    }
    public record Code(@NotBlank @Pattern(regexp="[0-9]{6}") String code) {
        @Override public String toString() {
            return "Code[REDACTED]";
        }
    }
    @PostMapping("/totp/enable") public Map<String, List<String>> enable(@Valid @RequestBody Code request) {
        return Map.of("recoveryCodes", service.enable(request.code()));
    }
    @PostMapping("/totp/disable") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void disable(@Valid @RequestBody SecurityCenterService.Proof request) {
        service.disable(request);
    }
}
