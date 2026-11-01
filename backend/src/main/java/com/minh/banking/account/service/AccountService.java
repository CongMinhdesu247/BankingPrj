package com.minh.banking.account.service;

import com.minh.banking.account.dto.request.ChangeAccountStatusRequest;
import com.minh.banking.account.dto.response.AccountResponse;
import com.minh.banking.account.entity.Account;
import com.minh.banking.account.enums.AccountStatus;
import com.minh.banking.account.mapper.AccountMapper;
import com.minh.banking.account.repository.AccountRepository;
import com.minh.banking.common.exception.BusinessException;
import com.minh.banking.common.response.PageResponse;
import com.minh.banking.customer.entity.Customer;
import com.minh.banking.customer.service.CustomerService;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AccountService {
    private final AccountRepository repository;
    private final CustomerService customers;
    private final AccountNumberGenerator numbers;
    private final EntityManager entityManager;
    private final AccountLockService accountLocks;
    private final com.minh.banking.audit.AuditService audit;

    public AccountService(AccountRepository repository, CustomerService customers,
            AccountNumberGenerator numbers, EntityManager entityManager, AccountLockService accountLocks,
            com.minh.banking.audit.AuditService audit) {
        this.repository = repository;
        this.customers = customers;
        this.numbers = numbers;
        this.entityManager = entityManager;
        this.accountLocks = accountLocks;
        this.audit=audit;
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('BANK_STAFF','ADMIN')")
    public AccountResponse create(UUID customerId) {
        UUID activeCustomerId = customers.requireActiveCustomerId(customerId);
        // Read-only association reference; customer business checks belong to CustomerService.
        Customer reference = entityManager.getReference(Customer.class, activeCustomerId);
        Account account = new Account(reference, numbers.next());
        var response=AccountMapper.toResponse(repository.saveAndFlush(account));
        audit.success(com.minh.banking.audit.AuditService.Action.ACCOUNT_OPENED,"ACCOUNT",response.id(),java.util.Map.of("customerId",customerId));
        return response;
    }

    public PageResponse<AccountResponse> list(UUID customerId, int page, int size) {
        customers.get(customerId); // Missing customer must return 404, not an empty list.
        var pageable = PageRequest.of(page, size, Sort.by("createdAt", "id"));
        return PageResponse.from(repository.findAllByCustomer_Id(customerId, pageable).map(AccountMapper::toResponse));
    }

    public AccountResponse get(UUID id) {
        return AccountMapper.toResponse(repository.findById(id).orElseThrow(AccountService::notFound));
    }

    @Transactional
    @org.springframework.security.access.prepost.PreAuthorize("hasAnyRole('BANK_STAFF','ADMIN')")
    public AccountResponse changeStatus(UUID id, ChangeAccountStatusRequest request) {
        Account account = accountLocks.lockOne(id);
        // All decisive checks use the state read AFTER acquiring the database row lock.
        if (account.getStatus() == request.status()) return AccountMapper.toResponse(account);
        if (account.getStatus() == AccountStatus.CLOSED) {
            throw new BusinessException("ACCOUNT_CLOSED", "A closed account cannot be reopened.", HttpStatus.CONFLICT);
        }
        if (request.status() == AccountStatus.CLOSED && account.getBalance().signum() != 0) {
            throw new BusinessException("ACCOUNT_BALANCE_NOT_ZERO", "An account with money cannot be closed.",
                HttpStatus.CONFLICT);
        }
        account.changeStatus(request.status());
        var response=AccountMapper.toResponse(repository.saveAndFlush(account));
        audit.success(com.minh.banking.audit.AuditService.Action.ACCOUNT_STATUS_CHANGED,"ACCOUNT",id,java.util.Map.of("status",request.status().name()));
        return response;
    }

    private static BusinessException notFound() {
        return new BusinessException("ACCOUNT_NOT_FOUND", "Account was not found.", HttpStatus.NOT_FOUND);
    }
}
