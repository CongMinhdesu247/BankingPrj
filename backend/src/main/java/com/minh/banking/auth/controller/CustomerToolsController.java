package com.minh.banking.auth.controller;

import com.minh.banking.audit.LoginAuditWriter;
import com.minh.banking.common.exception.*;
import com.minh.banking.common.response.PageResponse;
import com.minh.banking.customer.dto.request.UpdateCustomerRequest;
import com.minh.banking.customer.dto.response.CustomerResponse;
import com.minh.banking.customer.service.SelfProfileService;
import com.minh.banking.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/me") @PreAuthorize("isAuthenticated()")
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class CustomerToolsController {
    private final JdbcTemplate jdbc;
    private final CurrentUser current;
    private final LoginAuditWriter fingerprints;
    private final SelfProfileService profiles;
    public CustomerToolsController(JdbcTemplate jdbc,CurrentUser current,LoginAuditWriter fingerprints,SelfProfileService profiles) {
        this.jdbc=jdbc;this.current=current;this.fingerprints=fingerprints;this.profiles=profiles;
    }
    @PatchMapping("/contact") @PreAuthorize("hasRole('CUSTOMER')") @Transactional
    public CustomerResponse contact(@Valid @RequestBody UpdateCustomerRequest request) { return profiles.update(request); }

    @GetMapping("/login-history")
    public PageResponse<Login> logins(@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        var user=current.get();
        String username=jdbc.queryForObject("SELECT username FROM users WHERE id=?",String.class,user.id());
        String fingerprint=fingerprints.fingerprint(username);
        String where=" WHERE user_id=? OR (user_id IS NULL AND username_fingerprint=?)";
        long total=jdbc.queryForObject("SELECT count(*) FROM login_history"+where,Long.class,user.id(),fingerprint);
        var rows=jdbc.query("SELECT success,created_at FROM login_history"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",
            (rs,n)->new Login(rs.getBoolean("success"),rs.getTimestamp("created_at").toInstant()),user.id(),fingerprint,size,(long)page*size);
        return page(rows,page,size,total);
    }
    @GetMapping("/statement") @PreAuthorize("hasRole('CUSTOMER')")
    public Statement statement(@RequestParam UUID accountId,
            @RequestParam @Pattern(regexp="20[0-9]{2}-(0[1-9]|1[0-2])") String month,
            @RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size) {
        var owned=jdbc.queryForList("SELECT account_number FROM accounts WHERE id=? AND customer_id=?",String.class,accountId,current.get().customerId());
        if (owned.isEmpty()) throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND,"Account was not found.");
        var period=YearMonth.parse(month); var zone=ZoneId.of("Asia/Ho_Chi_Minh");
        var start=Timestamp.from(period.atDay(1).atStartOfDay(zone).toInstant());
        var end=Timestamp.from(period.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant());
        // Reconstruct from the current book balance, including any documented initial reserve.
        // Summing movements also avoids ambiguous ordering for equal posting timestamps.
        var opening=jdbc.queryForObject("""
            SELECT a.balance-coalesce((SELECT sum(CASE WHEN direction='CREDIT' THEN amount ELSE -amount END)
              FROM transaction_entries WHERE account_id=a.id AND created_at>=?),0)
            FROM accounts a WHERE a.id=?
            """,BigDecimal.class,start,accountId);
        var totals=jdbc.queryForMap("""
            SELECT coalesce(sum(amount) FILTER (WHERE direction='CREDIT'),0) incoming,
              coalesce(sum(amount) FILTER (WHERE direction='DEBIT'),0) outgoing,count(*) total
            FROM transaction_entries WHERE account_id=? AND created_at>=? AND created_at<?
            """,accountId,start,end);
        var incoming=(BigDecimal)totals.get("incoming"); var outgoing=(BigDecimal)totals.get("outgoing");
        long total=((Number)totals.get("total")).longValue();
        var rows=jdbc.query("""
            SELECT t.id,t.reference,t.description,e.direction,e.amount,e.balance_after,e.created_at
            FROM transaction_entries e JOIN transactions t ON t.id=e.transaction_id
            WHERE e.account_id=? AND e.created_at>=? AND e.created_at<?
            ORDER BY e.created_at DESC,e.id DESC LIMIT ? OFFSET ?
            """,(rs,n)->new StatementRow(rs.getObject("id",UUID.class),rs.getString("reference"),rs.getString("description"),
                rs.getString("direction"),rs.getBigDecimal("amount").toPlainString(),rs.getBigDecimal("balance_after").toPlainString(),
                rs.getTimestamp("created_at").toInstant()),accountId,start,end,size,(long)page*size);
        return new Statement(accountId,owned.getFirst(),month,opening.toPlainString(),incoming.toPlainString(),outgoing.toPlainString(),
            opening.add(incoming).subtract(outgoing).toPlainString(),page(rows,page,size,total));
    }
    private <T> PageResponse<T> page(List<T> rows,int page,int size,long total) {
        return new PageResponse<>(rows,page,size,total,(int)((total+size-1)/size),page==0,(long)(page+1)*size>=total);
    }
    public record Login(boolean success,Instant createdAt) {}
    public record StatementRow(UUID transactionId,String reference,String description,String direction,String amount,String balanceAfter,Instant createdAt) {}
    public record Statement(UUID accountId,String accountNumber,String month,String openingBalance,String incoming,String outgoing,String closingBalance,PageResponse<StatementRow> entries) {}
}
