package com.minh.banking.limit;

import com.minh.banking.account.service.AccountLockService;
import com.minh.banking.audit.AuditService;
import com.minh.banking.common.exception.*;
import com.minh.banking.common.response.PageResponse;
import com.minh.banking.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1") @Transactional
public class PersonalLimitController {
    private final JdbcTemplate jdbc;private final CurrentUser current;private final AccountLockService locks;private final TransferLimitService limits;private final AuditService audit;
    public PersonalLimitController(JdbcTemplate jdbc,CurrentUser current,AccountLockService locks,TransferLimitService limits,AuditService audit){this.jdbc=jdbc;this.current=current;this.locks=locks;this.limits=limits;this.audit=audit;}
    public record Change(@NotNull UUID accountId,@NotBlank @Pattern(regexp="[0-9]{1,17}") String perTransferAmount,@NotBlank @Pattern(regexp="[0-9]{1,17}") String dailyAmount,@Size(max=1000) String reason){}
    public record Review(@NotNull Boolean approve,@NotBlank @Size(max=1000) String note){}
    public record Request(UUID id,UUID accountId,String accountNumber,String username,String perTransferAmount,String dailyAmount,String reason,String status,String reviewNote,Instant createdAt){}
    private static final String SELECT="SELECT r.*,a.account_number,u.username FROM limit_change_requests r JOIN accounts a ON a.id=r.account_id JOIN users u ON u.id=r.user_id";
    private final org.springframework.jdbc.core.RowMapper<Request> mapper=(rs,n)->new Request(rs.getObject("id",UUID.class),rs.getObject("account_id",UUID.class),rs.getString("account_number"),rs.getString("username"),rs.getBigDecimal("per_transfer_amount").toPlainString(),rs.getBigDecimal("daily_amount").toPlainString(),rs.getString("reason"),rs.getString("status"),rs.getString("review_note"),rs.getTimestamp("created_at").toInstant());
    @GetMapping("/me/limit-settings") @PreAuthorize("hasRole('CUSTOMER')") @Transactional(readOnly=true)
    public Map<String,Object> settings(@RequestParam UUID accountId){var effective=limits.mine(accountId);return Map.of("effective",effective,"ceiling",limits.ceiling(accountId));}
    private void lockOwned(UUID id){var a=locks.lockOne(id);if(!a.getCustomerId().equals(current.get().customerId()))throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND,"Account was not found.");if(a.getStatus()!=com.minh.banking.account.enums.AccountStatus.ACTIVE)throw new BusinessException(ErrorCode.ACCOUNT_NOT_ACTIVE,"Account is not active.");}
    private BigDecimal[] amounts(Change request){var per=new BigDecimal(request.perTransferAmount());var daily=new BigDecimal(request.dailyAmount());if(per.signum()<=0||daily.compareTo(per)<0)throw new BusinessException(ErrorCode.INVALID_LIMIT,"Invalid limits.");return new BigDecimal[]{per,daily};}
    @PutMapping("/me/personal-transfer-limits") @PreAuthorize("hasRole('CUSTOMER')")
    public TransferLimitService.LimitResponse lower(@Valid @RequestBody Change request){lockOwned(request.accountId());var values=amounts(request);var before=limits.mine(request.accountId());
        if(values[0].compareTo(new BigDecimal(before.perTransferAmount()))>0||values[1].compareTo(new BigDecimal(before.dailyAmount()))>0)throw new BusinessException(ErrorCode.LIMIT_INCREASE_REQUIRED,"Increasing a limit requires approval.");
        save(request.accountId(),values[0],values[1]);audit.success(AuditService.Action.TRANSFER_LIMIT_UPDATED,"ACCOUNT",request.accountId(),Map.of("scope","PERSONAL_REDUCTION","perTransferAmount",request.perTransferAmount(),"dailyAmount",request.dailyAmount()));return limits.mine(request.accountId());}
    @PostMapping("/me/limit-requests") @PreAuthorize("hasRole('CUSTOMER')") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Request create(@Valid @RequestBody Change request){lockOwned(request.accountId());var values=amounts(request);var ceiling=limits.ceiling(request.accountId());var effective=limits.mine(request.accountId());
        if(request.reason()==null||request.reason().isBlank())throw new BusinessException(ErrorCode.INVALID_REQUEST,"Reason is required.");
        if(values[0].compareTo(new BigDecimal(ceiling.perTransferAmount()))>0||values[1].compareTo(new BigDecimal(ceiling.dailyAmount()))>0)throw new BusinessException(ErrorCode.INVALID_LIMIT,"Requested limits exceed the bank ceiling.");
        if(values[0].compareTo(new BigDecimal(effective.perTransferAmount()))<=0&&values[1].compareTo(new BigDecimal(effective.dailyAmount()))<=0)throw new BusinessException(ErrorCode.INVALID_LIMIT,"Use the reduction form for lower limits.");
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO limit_change_requests(id,account_id,user_id,per_transfer_amount,daily_amount,reason) VALUES (?,?,?,?,?,?)",id,request.accountId(),current.get().id(),values[0],values[1],request.reason().strip());audit.success(AuditService.Action.LIMIT_REQUESTED,"LIMIT_REQUEST",id,Map.of());return get(id,false);}
    private Request get(UUID id,boolean lock){return jdbc.query(SELECT+" WHERE r.id=?"+(lock?" FOR UPDATE OF r":""),mapper,id).stream().findFirst().orElseThrow(()->new BusinessException(ErrorCode.REQUEST_NOT_FOUND,"Request was not found."));}
    @GetMapping({"/me/limit-requests","/staff/limit-requests"}) @PreAuthorize("hasAnyRole('CUSTOMER','ADMIN')") @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResponse<Request> list(@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size){
        boolean admin=current.get().roles().contains(com.minh.banking.user.enums.RoleCode.ADMIN);var args=new ArrayList<Object>();String where=admin?"":" WHERE r.user_id=?";if(!admin)args.add(current.get().id());
        long total=jdbc.queryForObject("SELECT count(*) FROM limit_change_requests r"+where,Long.class,args.toArray());args.add(size);args.add((long)page*size);var rows=jdbc.query(SELECT+where+" ORDER BY r.created_at DESC,r.id DESC LIMIT ? OFFSET ?",mapper,args.toArray());return new PageResponse<>(rows,page,size,total,(int)((total+size-1)/size),page==0,(long)(page+1)*size>=total);
    }
    @PatchMapping("/staff/limit-requests/{id}") @PreAuthorize("hasRole('ADMIN')")
    public Request review(@PathVariable UUID id,@Valid @RequestBody Review request){var initial=get(id,false);var account=locks.lockOne(initial.accountId());var row=get(id,true);if(!row.status().equals("PENDING"))throw new BusinessException(ErrorCode.INVALID_WORKFLOW_STATE,"Request was already reviewed.");
        if(request.approve()){
            var ceiling=limits.ceiling(row.accountId());if(account.getStatus()!=com.minh.banking.account.enums.AccountStatus.ACTIVE||new BigDecimal(row.perTransferAmount()).compareTo(new BigDecimal(ceiling.perTransferAmount()))>0||new BigDecimal(row.dailyAmount()).compareTo(new BigDecimal(ceiling.dailyAmount()))>0)throw new BusinessException(ErrorCode.INVALID_LIMIT,"The account or bank ceiling changed; reject and request a new application.");
            save(row.accountId(),new BigDecimal(row.perTransferAmount()),new BigDecimal(row.dailyAmount()));}
        jdbc.update("UPDATE limit_change_requests SET status=?,reviewed_by=?,review_note=?,reviewed_at=now() WHERE id=?",request.approve()?"APPROVED":"REJECTED",current.get().id(),request.note().strip(),id);audit.success(AuditService.Action.LIMIT_REVIEWED,"LIMIT_REQUEST",id,Map.of("approved",request.approve()));return get(id,false);
    }
    private void save(UUID id,BigDecimal per,BigDecimal daily){jdbc.update("INSERT INTO personal_transfer_limits(account_id,per_transfer_amount,daily_amount) VALUES (?,?,?) ON CONFLICT(account_id) DO UPDATE SET per_transfer_amount=EXCLUDED.per_transfer_amount,daily_amount=EXCLUDED.daily_amount,updated_at=now()",id,per,daily);}
}
