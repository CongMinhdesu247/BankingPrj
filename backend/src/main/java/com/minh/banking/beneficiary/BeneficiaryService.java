package com.minh.banking.beneficiary;

import com.minh.banking.account.service.AccountService;
import com.minh.banking.audit.AuditService;
import com.minh.banking.common.exception.*;
import com.minh.banking.common.response.PageResponse;
import com.minh.banking.security.CurrentUser;
import jakarta.validation.constraints.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true) @PreAuthorize("hasRole('CUSTOMER')")
public class BeneficiaryService {
    private final JdbcTemplate jdbc;
    private final CurrentUser current;
    private final AccountService accounts;
    private final AuditService audit;
    private final Clock clock;
    public BeneficiaryService(JdbcTemplate jdbc, CurrentUser current, AccountService accounts, AuditService audit, Clock clock) {
        this.jdbc=jdbc; this.current=current; this.accounts=accounts; this.audit=audit; this.clock=clock;
    }
    public record CreateRequest(@NotNull UUID destinationAccountId,@Size(max=100) String nickname) {}
    public record UpdateRequest(@Size(max=100) String nickname) {}
    public record Response(UUID id,UUID destinationAccountId,String destinationAccountNumber,String nickname,Instant createdAt) {}
    private static final String SELECT="SELECT b.*,a.account_number AS destination_account_number FROM beneficiaries b JOIN accounts a ON a.id=b.destination_account_id";
    private static final RowMapper<Response> ROW=(rs,n)->new Response(rs.getObject("id",UUID.class),
        rs.getObject("destination_account_id",UUID.class),rs.getString("destination_account_number"),rs.getString("nickname"),rs.getTimestamp("created_at").toInstant());
    public PageResponse<Response> list(int page,int size) {
        UUID owner=current.get().customerId();
        long count=jdbc.queryForObject("SELECT count(*) FROM beneficiaries WHERE owner_customer_id=? AND deleted_at IS NULL",Long.class,owner);
        var rows=jdbc.query(SELECT+"""
             WHERE b.owner_customer_id=? AND b.deleted_at IS NULL
            ORDER BY b.created_at DESC,b.id DESC LIMIT ? OFFSET ?
            """,ROW,owner,size,(long)page*size);
        return PageResponse.from(new PageImpl<>(rows,PageRequest.of(page,size),count));
    }
    public Response get(UUID id) { return find(id,false); }
    @Transactional
    public Response create(CreateRequest request) {
        accounts.get(request.destinationAccountId()); // No balance or other customer's identity in response.
        UUID id=UUID.randomUUID();
        Instant now=clock.instant();
        var rows=jdbc.query("""
            INSERT INTO beneficiaries(id,owner_customer_id,destination_account_id,nickname,created_at)
            VALUES (?,?,?,?,?) ON CONFLICT(owner_customer_id,destination_account_id)
            WHERE deleted_at IS NULL DO NOTHING RETURNING id
            """,(rs,n)->rs.getObject("id",UUID.class),id,current.get().customerId(),request.destinationAccountId(),normalize(request.nickname()),Timestamp.from(now));
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.BENEFICIARY_CONFLICT,"Beneficiary already exists.");
        audit.success(AuditService.Action.BENEFICIARY_CREATED,"BENEFICIARY",id,Map.of("destinationAccountId",request.destinationAccountId()));
        return find(id,false);
    }
    @Transactional
    public Response update(UUID id,UpdateRequest request) {
        find(id,true);
        jdbc.update("UPDATE beneficiaries SET nickname=? WHERE id=?",normalize(request.nickname()),id);
        audit.success(AuditService.Action.BENEFICIARY_UPDATED,"BENEFICIARY",id,Map.of("nicknameChanged",true));
        return find(id,false);
    }
    @Transactional
    public void delete(UUID id) {
        var original=find(id,true);
        Instant now=clock.instant();
        // Preserve timestamps even if wall clock is adjusted backwards.
        jdbc.update("UPDATE beneficiaries SET deleted_at=? WHERE id=?",Timestamp.from(now.isBefore(original.createdAt())?original.createdAt():now),id);
        audit.success(AuditService.Action.BENEFICIARY_DELETED,"BENEFICIARY",id,Map.of());
    }
    private Response find(UUID id,boolean lock) {
        var rows=jdbc.query(SELECT+" WHERE b.id=? AND b.owner_customer_id=? AND b.deleted_at IS NULL"+(lock?" FOR UPDATE OF b":""),
            ROW,id,current.get().customerId());
        if (rows.isEmpty()) throw new BusinessException(ErrorCode.BENEFICIARY_NOT_FOUND,"Beneficiary was not found.");
        return rows.getFirst();
    }
    private static String normalize(String value) { return value==null || value.isBlank()?null:value.strip(); }
}
