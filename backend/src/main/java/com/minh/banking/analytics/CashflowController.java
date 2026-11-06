package com.minh.banking.analytics;

import com.minh.banking.security.CurrentUser;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/cashflow")
@PreAuthorize("hasRole('CUSTOMER')")
public class CashflowController {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final JdbcTemplate jdbc;
    private final CurrentUser current;
    private final Clock clock;
    public CashflowController(JdbcTemplate jdbc, CurrentUser current, Clock clock) {
        this.jdbc = jdbc;
        this.current = current;
        this.clock = clock;
    }
    @GetMapping @Transactional(readOnly=true)
    public ResponseEntity<Cashflow> get(@RequestParam(defaultValue="30") @Min(7) @Max(30) int days) {
        var asOf = clock.instant();
        var end = asOf.atZone(ZONE).toLocalDate();
        var start = end.minusDays(days-1L);
        // Filter on the indexed timestamp before grouping; ownership always comes from the authenticated principal.
        var rows = jdbc.query("""
            SELECT (e.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS flow_date,
              coalesce(sum(e.amount) FILTER (WHERE e.direction='CREDIT'),0) incoming,
              coalesce(sum(e.amount) FILTER (WHERE e.direction='DEBIT'),0) outgoing
            FROM accounts a JOIN transaction_entries e ON e.account_id=a.id
            WHERE a.customer_id=? AND e.created_at>=? AND e.created_at<=?
            GROUP BY 1 ORDER BY 1
            """, (rs, n) -> new Day(rs.getDate("flow_date").toLocalDate().toString(), rs.getBigDecimal("incoming").toPlainString(), rs.getBigDecimal("outgoing").toPlainString()),
            current.get().customerId(), Timestamp.from(start.atStartOfDay(ZONE).toInstant()), Timestamp.from(asOf));
        var byDate = new HashMap<String, Day>();
        rows.forEach(row -> byDate.put(row.date(), row));
        var series = new ArrayList<Day>();
        var incoming = BigDecimal.ZERO;
        var outgoing = BigDecimal.ZERO;
        for (var day=start; !day.isAfter(end); day=day.plusDays(1)) {
            var item = byDate.getOrDefault(day.toString(), new Day(day.toString(), "0", "0"));
            series.add(item);
            incoming = incoming.add(new BigDecimal(item.incoming()));
            outgoing = outgoing.add(new BigDecimal(item.outgoing()));
        }
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new Cashflow(start.toString(), end.toString(), ZONE.getId(), asOf,
            incoming.toPlainString(), outgoing.toPlainString(), incoming.subtract(outgoing).toPlainString(), series));
    }
    public record Day(String date, String incoming, String outgoing) {}
    public record Cashflow(String from, String to, String timezone, Instant asOf, String incoming, String outgoing, String net, List<Day> series) {}
}
