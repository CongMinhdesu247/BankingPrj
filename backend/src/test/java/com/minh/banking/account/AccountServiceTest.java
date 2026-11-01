package com.minh.banking.account;

import com.minh.banking.account.dto.request.ChangeAccountStatusRequest;
import com.minh.banking.account.entity.Account;
import com.minh.banking.account.enums.AccountStatus;
import com.minh.banking.account.repository.AccountRepository;
import com.minh.banking.account.service.*;
import com.minh.banking.common.exception.BusinessException;
import com.minh.banking.customer.entity.Customer;
import com.minh.banking.customer.service.CustomerService;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {
    @Mock AccountRepository repository;
    @Mock CustomerService customers;
    @Mock AccountNumberGenerator numbers;
    @Mock EntityManager entityManager;
    @Mock AccountLockService accountLocks;
    AccountService service;

    @BeforeEach
    void setUp() { service = new AccountService(repository, customers, numbers, entityManager, accountLocks, mock(com.minh.banking.audit.AuditService.class)); }

    @Test
    void opensActiveVndAccountWithZeroBalanceAndServerGeneratedNumber() {
        UUID customerId = UUID.randomUUID();
        Customer customer = mock(Customer.class);
        when(customer.getId()).thenReturn(customerId);
        when(customers.requireActiveCustomerId(customerId)).thenReturn(customerId);
        when(entityManager.getReference(Customer.class, customerId)).thenReturn(customer);
        when(numbers.next()).thenReturn("1000000000001");
        when(repository.saveAndFlush(any(Account.class))).thenAnswer(call -> call.getArgument(0));
        var result = service.create(customerId);
        assertThat(result.customerId()).isEqualTo(customerId);
        assertThat(result.balance()).isEqualTo("0.00");
        assertThat(result.currency()).isEqualTo("VND");
        assertThat(result.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(result.accountNumber()).isEqualTo("1000000000001");
    }

    @Test
    void customerPolicyRejectionDoesNotWriteAnAccount() {
        UUID id = UUID.randomUUID();
        when(customers.requireActiveCustomerId(id)).thenThrow(
            new BusinessException("CUSTOMER_NOT_ACTIVE", "Inactive", HttpStatus.CONFLICT));
        assertThatThrownBy(() -> service.create(id)).isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("CUSTOMER_NOT_ACTIVE"));
        verifyNoInteractions(repository, numbers, entityManager);
    }

    @Test
    void fundedAccountCannotCloseAfterItIsLocked() {
        UUID id = UUID.randomUUID();
        Account account = new Account(mock(Customer.class), "1000000000001");
        ReflectionTestUtils.setField(account, "balance", new BigDecimal("100.00"));
        when(accountLocks.lockOne(id)).thenReturn(account);
        assertThatThrownBy(() -> service.changeStatus(id, new ChangeAccountStatusRequest(AccountStatus.CLOSED)))
            .isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("ACCOUNT_BALANCE_NOT_ZERO"));
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void closedAccountIsTerminalButRepeatedCloseIsHarmless() {
        UUID id = UUID.randomUUID();
        Account account = new Account(mock(Customer.class), "1000000000001");
        account.changeStatus(AccountStatus.CLOSED);
        when(accountLocks.lockOne(id)).thenReturn(account);
        assertThat(service.changeStatus(id, new ChangeAccountStatusRequest(AccountStatus.CLOSED)).status())
            .isEqualTo(AccountStatus.CLOSED);
        assertThatThrownBy(() -> service.changeStatus(id, new ChangeAccountStatusRequest(AccountStatus.ACTIVE)))
            .isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("ACCOUNT_CLOSED"));
        verify(repository, never()).saveAndFlush(any());
    }

    @Test
    void statusChangeOfMissingAccountHasSpecificError() {
        UUID id = UUID.randomUUID();
        when(accountLocks.lockOne(id)).thenThrow(new BusinessException(
            "ACCOUNT_NOT_FOUND", "Account was not found.", HttpStatus.NOT_FOUND));
        assertThatThrownBy(() -> service.changeStatus(id, new ChangeAccountStatusRequest(AccountStatus.FROZEN)))
            .isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("ACCOUNT_NOT_FOUND"));
    }
}
