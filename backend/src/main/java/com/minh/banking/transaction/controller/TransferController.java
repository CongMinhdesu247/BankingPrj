package com.minh.banking.transaction.controller;

import com.minh.banking.transaction.dto.request.TransferRequest;
import com.minh.banking.transaction.dto.response.TransactionDetailResponse;
import com.minh.banking.transaction.service.TransferFacade;
import jakarta.validation.Valid;
import java.net.URI;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@org.springframework.security.access.prepost.PreAuthorize("hasRole('CUSTOMER')")
@RequestMapping("/api/v1/transfers")
public class TransferController {
    private final TransferFacade service;
    public TransferController(TransferFacade service) { this.service = service; }

    @PostMapping
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "201", description = "Transfer committed with debit and credit entries")
    public ResponseEntity<TransactionDetailResponse> transfer(
            @io.swagger.v3.oas.annotations.Parameter(required=true,description="Unique request key (1-128 safe ASCII characters); reuse this key for retries within 7 days")
            @RequestHeader(value="Idempotency-Key",required=false) String key,
            @Valid @RequestBody TransferRequest request) {
        var result = service.transfer(key,request);
        var response=result.response();
        return ResponseEntity.created(URI.create("/api/v1/transactions/" + response.transaction().id()))
            .header("Idempotency-Replayed",Boolean.toString(result.replayed())).body(response);
    }
}
