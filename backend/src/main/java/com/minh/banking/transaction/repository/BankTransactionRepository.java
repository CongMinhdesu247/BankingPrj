package com.minh.banking.transaction.repository;

import com.minh.banking.transaction.entity.BankTransaction;
import java.util.UUID;
import org.springframework.data.jpa.repository.*;

public interface BankTransactionRepository extends JpaRepository<BankTransaction, UUID>, JpaSpecificationExecutor<BankTransaction> {}
