package com.minh.banking.transaction.service;

import com.minh.banking.account.entity.Account;
import com.minh.banking.transaction.entity.*;
import com.minh.banking.transaction.enums.EntryDirection;
import com.minh.banking.transaction.repository.TransactionEntryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class TransactionLedgerWriter {
    private final TransactionEntryRepository entries;

    public TransactionLedgerWriter(TransactionEntryRepository entries) { this.entries = entries; }

    public TransactionEntry record(BankTransaction transaction, Account account, EntryDirection direction) {
        return entries.saveAndFlush(new TransactionEntry(transaction, account, direction));
    }
}
