package com.minh.banking.analytics;

import com.minh.banking.security.*;
import com.minh.banking.user.enums.RoleCode;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class CashflowControllerTest {
    @Test @SuppressWarnings("unchecked") void computesVietnamMidnightAndKeepsZeroDaysAndExactAmounts() {
        var jdbc = mock(JdbcTemplate.class);
        var current = mock(CurrentUser.class);
        var customer = UUID.randomUUID();
        when(current.get()).thenReturn(new BankingPrincipal(UUID.randomUUID(), "customer", customer, Set.of(RoleCode.CUSTOMER)));
        when(jdbc.query(anyString(), any(RowMapper.class), eq(customer), any(Timestamp.class), any(Timestamp.class)))
        .thenReturn(List.of(new CashflowController.Day("2026-10-31", "99999999999999999.00", "100.00")));
        var asOf = Instant.parse("2026-11-05T17:01:00Z");
        var response = new CashflowController(jdbc, current, Clock.fixed(asOf, ZoneOffset.UTC)).get(7).getBody();
        assertThat(response.from()).isEqualTo("2026-10-31");
        assertThat(response.to()).isEqualTo("2026-11-06");
        assertThat(response.series()).hasSize(7);
        assertThat(response.series().get(1).incoming()).isEqualTo("0");
        assertThat(response.incoming()).isEqualTo("99999999999999999.00");
        assertThat(response.net()).isEqualTo("99999999999999899.00");
        var start = ArgumentCaptor.forClass(Timestamp.class);
        var end = ArgumentCaptor.forClass(Timestamp.class);
        verify(jdbc).query(anyString(), any(RowMapper.class), eq(customer), start.capture(), end.capture());
        assertThat(start.getValue().toInstant()).isEqualTo(Instant.parse("2026-10-30T17:00:00Z"));
        assertThat(end.getValue().toInstant()).isEqualTo(asOf);
    }
}
