package com.minh.banking.notification;

import com.minh.banking.common.response.PageResponse;
import jakarta.validation.constraints.*;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/me/notifications") @PreAuthorize("isAuthenticated()")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service=service; }
    @GetMapping("/summary") public NotificationService.Summary summary(){return service.summary();}
    @GetMapping public PageResponse<NotificationService.Notification> list(@RequestParam(defaultValue="0") @Min(0) int page,
        @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,@RequestParam(defaultValue="false") boolean unreadOnly) { return service.list(page,size,unreadOnly); }
    @PatchMapping("/{id}/read") @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID id) { service.read(id); }
}
