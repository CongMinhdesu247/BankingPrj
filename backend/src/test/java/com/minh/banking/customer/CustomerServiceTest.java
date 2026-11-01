package com.minh.banking.customer;

import com.minh.banking.common.exception.BusinessException;
import com.minh.banking.customer.dto.request.*;
import com.minh.banking.customer.entity.Customer;
import com.minh.banking.customer.repository.CustomerRepository;
import com.minh.banking.customer.service.CustomerService;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CustomerServiceTest {
    @Mock CustomerRepository repository;
    CustomerService service;

    @BeforeEach
    void setUp() { service = new CustomerService(repository, mock(com.minh.banking.audit.AuditService.class),
        mock(org.springframework.context.ApplicationEventPublisher.class)); }

    @Test
    void normalizesCustomerIdentityAndContactBeforeSaving() {
        when(repository.saveAndFlush(any(Customer.class))).thenAnswer(call -> call.getArgument(0));
        var result = service.create(new CreateCustomerRequest("kh001", " Nguyen Van A ",
            "ALICE@EXAMPLE.COM", ""));
        assertThat(result.customerCode()).isEqualTo("KH001");
        assertThat(result.fullName()).isEqualTo("Nguyen Van A");
        assertThat(result.email()).isEqualTo("alice@example.com");
        assertThat(result.phone()).isNull();
    }

    @Test
    void patchChangesOnlySuppliedContactAndAllowsExplicitClear() {
        UUID id = UUID.randomUUID();
        Customer customer = new Customer("KH001", "Nguyen Van A", "a@example.com", "0912345678");
        when(repository.findById(id)).thenReturn(Optional.of(customer));
        when(repository.saveAndFlush(customer)).thenReturn(customer);
        var result = service.update(id, new UpdateCustomerRequest("", null));
        assertThat(result.email()).isNull();
        assertThat(result.phone()).isEqualTo("0912345678");
        assertThat(result.customerCode()).isEqualTo("KH001");
    }

    @Test
    void rejectsEmptyPatchWithoutWriting() {
        assertThatThrownBy(() -> service.update(UUID.randomUUID(), new UpdateCustomerRequest(null, null)))
            .isInstanceOfSatisfying(BusinessException.class,
                exception -> assertThat(exception.getCode()).isEqualTo("EMPTY_UPDATE"));
        verifyNoInteractions(repository);
    }

    @Test
    void missingCustomerHasSpecificBusinessError() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.get(id)).isInstanceOfSatisfying(BusinessException.class,
            exception -> assertThat(exception.getCode()).isEqualTo("CUSTOMER_NOT_FOUND"));
    }
}
