package com.minh.banking.account.service;

import com.minh.banking.account.entity.Account;
import com.minh.banking.account.enums.AccountStatus;
import com.minh.banking.account.repository.AccountRepository;
import com.minh.banking.common.exception.*;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Destination lookup is a separate projection; it never grants access to the account detail. */
@Service @Transactional(readOnly=true) @PreAuthorize("hasRole('CUSTOMER')")
public class RecipientQueryService {
    private final AccountRepository accounts;
    public RecipientQueryService(AccountRepository accounts) { this.accounts=accounts; }
    public record Recipient(UUID id,String accountNumber,String currency,AccountStatus status) {}
    public Recipient byNumber(String number) { return project(accounts.findByAccountNumber(number).orElseThrow(RecipientQueryService::missing)); }
    public Recipient byId(UUID id) { return project(accounts.findById(id).orElseThrow(RecipientQueryService::missing)); }
    private static Recipient project(Account account) { return new Recipient(account.getId(),account.getAccountNumber(),account.getCurrency(),account.getStatus()); }
    private static BusinessException missing() { return new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND,"Account was not found."); }
}
