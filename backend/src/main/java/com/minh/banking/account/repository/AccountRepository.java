package com.minh.banking.account.repository;

import com.minh.banking.account.entity.Account;
import java.util.UUID;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

public interface AccountRepository extends JpaRepository<Account, UUID> {
    Page<Account> findAllByCustomer_Id(UUID customerId, Pageable pageable);
    Optional<Account> findByAccountNumber(String accountNumber);
}
