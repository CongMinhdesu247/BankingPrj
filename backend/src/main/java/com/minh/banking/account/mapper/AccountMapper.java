package com.minh.banking.account.mapper;

import com.minh.banking.account.dto.response.AccountResponse;
import com.minh.banking.account.entity.Account;

public final class AccountMapper {
    private AccountMapper() {}

    public static AccountResponse toResponse(Account account) {
        return new AccountResponse(account.getId(), account.getCustomerId(), account.getAccountNumber(),
            account.getCurrency(), account.getBalance().toPlainString(), account.getStatus(),
            account.getCreatedAt(), account.getUpdatedAt());
    }
}
