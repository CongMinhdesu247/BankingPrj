package com.minh.banking.account.controller;

import com.minh.banking.account.service.RecipientQueryService;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/transfer-recipients") @PreAuthorize("hasRole('CUSTOMER')")
public class RecipientController {
    private final RecipientQueryService service;
    public RecipientController(RecipientQueryService service) {
        this.service = service;
    }
    @GetMapping
    public RecipientQueryService.Recipient byNumber(@RequestParam @NotBlank @Size(max=30) String accountNumber) {
        return service.byNumber(accountNumber);
    }
    @GetMapping("/{id}")
    public RecipientQueryService.Recipient byId(@PathVariable UUID id) {
        return service.byId(id);
    }
}
