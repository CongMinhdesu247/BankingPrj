package com.minh.banking.beneficiary;

import com.minh.banking.common.response.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/me/beneficiaries")
public class BeneficiaryController {
    private final BeneficiaryService service;
    public BeneficiaryController(BeneficiaryService service) { this.service=service; }
    @GetMapping
    public PageResponse<BeneficiaryService.Response> list(@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) { return service.list(page,size); }
    @GetMapping("/{id}") public BeneficiaryService.Response get(@PathVariable UUID id) { return service.get(id); }
    @PostMapping
    @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode="201",description="Beneficiary created")
    public ResponseEntity<BeneficiaryService.Response> create(@Valid @RequestBody BeneficiaryService.CreateRequest request) {
        var response=service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/me/beneficiaries/"+response.id())).body(response);
    }
    @PatchMapping("/{id}")
    public BeneficiaryService.Response update(@PathVariable UUID id,@Valid @RequestBody BeneficiaryService.UpdateRequest request) {
        return service.update(id,request);
    }
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) { service.delete(id); return ResponseEntity.noContent().build(); }
}
