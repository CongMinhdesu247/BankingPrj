package com.minh.banking.customer.service;

import com.minh.banking.common.exception.BusinessException;
import com.minh.banking.common.response.PageResponse;
import com.minh.banking.customer.dto.request.*;
import com.minh.banking.customer.dto.response.CustomerResponse;
import com.minh.banking.customer.entity.Customer;
import com.minh.banking.customer.enums.CustomerStatus;
import com.minh.banking.customer.mapper.CustomerMapper;
import com.minh.banking.customer.repository.CustomerRepository;
import java.util.Locale;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class CustomerService {
    private final CustomerRepository repository;
    private final com.minh.banking.audit.AuditService audit;
    private final org.springframework.context.ApplicationEventPublisher events;

    public CustomerService(CustomerRepository repository, com.minh.banking.audit.AuditService audit,
            org.springframework.context.ApplicationEventPublisher events) { this.repository = repository; this.audit=audit; this.events=events; }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('BANK_STAFF','ADMIN')")
    public CustomerResponse create(CreateCustomerRequest request) {
        Customer customer = new Customer(request.customerCode().strip().toUpperCase(Locale.ROOT),
            request.fullName().strip(), normalizeEmail(request.email()), normalizeOptional(request.phone()));
        // Flush lets the database arbitrate duplicate requests, including concurrent inserts.
        var response=CustomerMapper.toResponse(repository.saveAndFlush(customer));
        audit.success(com.minh.banking.audit.AuditService.Action.CUSTOMER_CREATED,"CUSTOMER",response.id(),java.util.Map.of());
        return response;
    }

    public CustomerResponse get(UUID id) {
        return CustomerMapper.toResponse(requireCustomer(id));
    }

    // Published internal API: account must not access CustomerRepository directly.
    public UUID requireActiveCustomerId(UUID id) {
        Customer customer = requireCustomer(id);
        if (customer.getStatus() != CustomerStatus.ACTIVE) {
            throw new BusinessException("CUSTOMER_NOT_ACTIVE", "Only an active customer can open an account.",
                HttpStatus.CONFLICT);
        }
        return customer.getId();
    }

    public PageResponse<CustomerResponse> list(int page, int size, String search) {
        String query = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
        Specification<Customer> filter = (root, criteria, builder) -> {
            if (query.isEmpty()) return builder.conjunction();
            String pattern = "%" + query.replace("\\", "\\\\").replace("%", "\\%")
                .replace("_", "\\_") + "%";
            return builder.or(
                builder.like(builder.lower(root.get("customerCode")), pattern, '\\'),
                builder.like(builder.lower(root.get("fullName")), pattern, '\\'),
                builder.like(builder.lower(root.get("email")), pattern, '\\'));
        };
        var pageable = PageRequest.of(page, size, Sort.by("createdAt", "id"));
        return PageResponse.from(repository.findAll(filter, pageable).map(CustomerMapper::toResponse));
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('BANK_STAFF','ADMIN')")
    public CustomerResponse update(UUID id, UpdateCustomerRequest request) {
        if (request.email() == null && request.phone() == null) {
            throw new BusinessException("EMPTY_UPDATE", "Provide email or phone to update.", HttpStatus.BAD_REQUEST);
        }
        Customer customer = requireCustomer(id);
        if (request.email() != null) customer.changeEmail(normalizeEmail(request.email()));
        if (request.phone() != null) customer.changePhone(normalizeOptional(request.phone()));
        var response=CustomerMapper.toResponse(repository.saveAndFlush(customer));
        audit.success(com.minh.banking.audit.AuditService.Action.CUSTOMER_UPDATED,"CUSTOMER",id,java.util.Map.of());
        events.publishEvent(new CustomerProfileChanged(id));
        return response;
    }

    private Customer requireCustomer(UUID id) {
        return repository.findById(id).orElseThrow(() -> new BusinessException(
            "CUSTOMER_NOT_FOUND", "Customer was not found.", HttpStatus.NOT_FOUND));
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String normalizeEmail(String value) {
        String normalized = normalizeOptional(value);
        return normalized == null ? null : normalized.toLowerCase(Locale.ROOT);
    }
}
