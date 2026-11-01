package com.minh.banking.limit;

import com.minh.banking.account.enums.AccountStatus;
import com.minh.banking.account.service.*;
import com.minh.banking.audit.AuditService;
import com.minh.banking.common.exception.*;
import com.minh.banking.security.*;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class TransferLimitService {
    private static final ZoneId ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final JdbcTemplate jdbc;
    private final AccountService accounts;
    private final AccountLockService locks;
    private final OwnershipService ownership;
    private final CurrentUser current;
    private final AuditService audit;
    private final Clock clock;
    private final BigDecimal defaultPer, defaultDaily;
    public TransferLimitService(JdbcTemplate jdbc, AccountService accounts, AccountLockService locks,
            OwnershipService ownership, CurrentUser current, AuditService audit, Clock clock,
            @Value("${banking.transfer.default-per-transfer:100000000}") String per,
            @Value("${banking.transfer.default-daily:500000000}") String daily) {
        this.jdbc=jdbc; this.accounts=accounts; this.locks=locks; this.ownership=ownership;
        this.current=current; this.audit=audit; this.clock=clock;
        defaultPer=money(per); defaultDaily=money(daily);
        if (defaultPer.compareTo(defaultDaily)>0) throw new IllegalArgumentException("Default transfer limit exceeds daily limit");
    }
    public record LimitResponse(UUID accountId, String perTransferAmount, String dailyAmount,
        String dailyUsed, String dailyRemaining, String currency, LocalDate date, String timezone, boolean defaultApplied) {}
    private record Limits(BigDecimal per, BigDecimal daily, boolean defaults) {}

    @Transactional(readOnly=true) @PreAuthorize("hasRole('CUSTOMER')")
    public LimitResponse mine(UUID accountId) {
        ownership.requireTransferSource(accounts.get(accountId).customerId());
        return response(accountId, clock.instant());
    }
    @Transactional @PreAuthorize("hasRole('ADMIN')")
    public LimitResponse update(UUID id, UpdateLimitRequest request) {
        BigDecimal per=money(request.perTransferAmount()), daily=money(request.dailyAmount());
        if (per.compareTo(daily)>0) throw new BusinessException(ErrorCode.INVALID_LIMIT, "Per-transfer limit must not exceed daily limit.");
        var account = locks.lockOne(id);
        if (account.getStatus()==AccountStatus.CLOSED) throw new BusinessException(ErrorCode.ACCOUNT_CLOSED, "A closed account cannot be updated.");
        Instant now=clock.instant();
        jdbc.update("""
            INSERT INTO transfer_limits(account_id,per_transfer_amount,daily_amount,updated_by,created_at,updated_at)
            VALUES (?,?,?,?,?,?) ON CONFLICT(account_id) DO UPDATE SET
            per_transfer_amount=EXCLUDED.per_transfer_amount,daily_amount=EXCLUDED.daily_amount,
            updated_by=EXCLUDED.updated_by,updated_at=EXCLUDED.updated_at
            """, id, per, daily, current.get().id(), Timestamp.from(now), Timestamp.from(now));
        audit.success(AuditService.Action.TRANSFER_LIMIT_UPDATED,"ACCOUNT",id,
            Map.of("perTransferAmount",per.toPlainString(),"dailyAmount",daily.toPlainString()));
        return response(id,now);
    }
    /** Caller holds the source account's PESSIMISTIC_WRITE lock until commit. */
    @Transactional(propagation=Propagation.MANDATORY)
    public void checkLocked(com.minh.banking.account.entity.Account account, BigDecimal amount, Instant now) {
        locks.requireWriteLock(account);
        UUID source=account.getId();
        var limit=limits(source);
        if (amount.compareTo(limit.per())>0) throw new BusinessException(ErrorCode.PER_TRANSFER_LIMIT_EXCEEDED,"Per-transfer limit exceeded.");
        if (used(source,now).add(amount).compareTo(limit.daily())>0) throw new BusinessException(ErrorCode.DAILY_LIMIT_EXCEEDED,"Daily transfer limit exceeded.");
    }
    private Limits limits(UUID source) {
        var bank=bankLimits(source);
        var personal=jdbc.query("SELECT per_transfer_amount,daily_amount FROM personal_transfer_limits WHERE account_id=?",(rs,n)->new Limits(rs.getBigDecimal(1),rs.getBigDecimal(2),false),source);
        return personal.isEmpty()?bank:new Limits(bank.per().min(personal.getFirst().per()),bank.daily().min(personal.getFirst().daily()),false);
    }
    public record Ceiling(String perTransferAmount,String dailyAmount){}
    @Transactional(readOnly=true)
    public Ceiling ceiling(UUID id){var bank=bankLimits(id);return new Ceiling(bank.per().toPlainString(),bank.daily().toPlainString());}
    private Limits bankLimits(UUID source) {
        var rows=jdbc.query("SELECT per_transfer_amount,daily_amount FROM transfer_limits WHERE account_id=?",
            (rs,n)->new Limits(rs.getBigDecimal(1),rs.getBigDecimal(2),false), source);
        return rows.isEmpty()?new Limits(defaultPer,defaultDaily,true):rows.getFirst();
    }
    private BigDecimal used(UUID source, Instant now) {
        LocalDate date=now.atZone(ZONE).toLocalDate();
        return jdbc.queryForObject("""
            SELECT coalesce(sum(amount),0) FROM transactions WHERE source_account_id=?
            AND type='INTERNAL_TRANSFER' AND status='COMPLETED' AND created_at>=? AND created_at<?
            """,BigDecimal.class,source,Timestamp.from(date.atStartOfDay(ZONE).toInstant()),
            Timestamp.from(date.plusDays(1).atStartOfDay(ZONE).toInstant()));
    }
    private LimitResponse response(UUID source,Instant now) {
        var limit=limits(source); var used=used(source,now);
        return new LimitResponse(source,limit.per().setScale(2).toPlainString(),limit.daily().setScale(2).toPlainString(),
            used.setScale(2).toPlainString(),limit.daily().subtract(used).max(BigDecimal.ZERO).setScale(2).toPlainString(),
            "VND",now.atZone(ZONE).toLocalDate(),ZONE.toString(),limit.defaults());
    }
    private static BigDecimal money(String value) {
        if (value==null || !value.matches("[0-9]{1,17}(?:\\.0{1,2})?") || new BigDecimal(value).signum()<=0)
            throw new BusinessException(ErrorCode.INVALID_LIMIT,"Limits must be positive whole VND decimal strings.");
        return new BigDecimal(value).setScale(2);
    }
}
