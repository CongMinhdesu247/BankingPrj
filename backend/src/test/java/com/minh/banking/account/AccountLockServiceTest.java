package com.minh.banking.account;

import com.minh.banking.account.entity.Account;
import com.minh.banking.account.service.AccountLockService;
import com.minh.banking.common.exception.BusinessException;
import jakarta.persistence.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountLockServiceTest {
    @Mock EntityManager entityManager;
    @Mock Query query;

    @Test
    void locksInPostgresUnsignedUuidOrderAndPreservesSourceDestinationForEitherDirection() {
        var low = UUID.fromString("10000000-0000-0000-0000-000000000001");
        var high = UUID.fromString("80000000-0000-0000-0000-000000000002");
        Account lowAccount = mock(Account.class);
        Account highAccount = mock(Account.class);
        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter("timeout", "2000ms")).thenReturn(query);
        when(entityManager.getReference(Account.class, low)).thenReturn(lowAccount);
        when(entityManager.getReference(Account.class, high)).thenReturn(highAccount);
        var service = new AccountLockService(entityManager, 2000);
        var forward = service.lockPair(high, low);
        var reverse = service.lockPair(low, high);
        var order = inOrder(entityManager);
        order.verify(entityManager).refresh(lowAccount, LockModeType.PESSIMISTIC_WRITE);
        order.verify(entityManager).refresh(highAccount, LockModeType.PESSIMISTIC_WRITE);
        order.verify(entityManager).refresh(lowAccount, LockModeType.PESSIMISTIC_WRITE);
        order.verify(entityManager).refresh(highAccount, LockModeType.PESSIMISTIC_WRITE);
        assertThat(forward.source()).isSameAs(highAccount);
        assertThat(forward.destination()).isSameAs(lowAccount);
        assertThat(reverse.source()).isSameAs(lowAccount);
    }

    @Test
    void rejectsSelfTransferBeforeTouchingDatabase() {
        UUID id = UUID.randomUUID();
        var service = new AccountLockService(entityManager, 2000);
        assertThatThrownBy(() -> service.lockPair(id, id)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(entityManager);
    }
}
