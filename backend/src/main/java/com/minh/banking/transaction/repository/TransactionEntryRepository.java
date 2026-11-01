package com.minh.banking.transaction.repository;

import com.minh.banking.transaction.entity.TransactionEntry;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionEntryRepository extends JpaRepository<TransactionEntry, UUID> {
    List<TransactionEntry> findAllByTransaction_Id(UUID transactionId);
}
