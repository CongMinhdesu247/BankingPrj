package com.minh.banking.transaction.controller;

import com.minh.banking.audit.AuditService;
import com.minh.banking.common.exception.*;
import com.minh.banking.common.response.PageResponse;
import com.minh.banking.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1/me/transfer-templates") @PreAuthorize("hasRole('CUSTOMER')")
@Transactional
public class TransferTemplateController {
    private final JdbcTemplate jdbc;private final CurrentUser current;private final AuditService audit;
    public TransferTemplateController(JdbcTemplate jdbc,CurrentUser current,AuditService audit){this.jdbc=jdbc;this.current=current;this.audit=audit;}
    public record Create(@NotBlank @Size(max=80) String name,@NotNull UUID sourceAccountId,@NotNull UUID destinationAccountId,
        @NotBlank @Pattern(regexp="[0-9]{1,17}") String amount,@Size(max=255) String description){}
    public record Template(UUID id,String name,UUID sourceAccountId,UUID destinationAccountId,String destinationAccountNumber,String amount,String description){}
    private final org.springframework.jdbc.core.RowMapper<Template> mapper=(rs,n)->new Template(rs.getObject("id",UUID.class),rs.getString("name"),rs.getObject("source_account_id",UUID.class),rs.getObject("destination_account_id",UUID.class),rs.getString("account_number"),rs.getBigDecimal("amount").toPlainString(),rs.getString("description"));
    @GetMapping @Transactional(readOnly=true)
    public PageResponse<Template> list(@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size){
        var user=current.get();long total=jdbc.queryForObject("SELECT count(*) FROM transfer_templates WHERE user_id=?",Long.class,user.id());
        var rows=jdbc.query("SELECT t.*,a.account_number FROM transfer_templates t JOIN accounts a ON a.id=t.destination_account_id WHERE t.user_id=? ORDER BY t.created_at DESC,t.id DESC LIMIT ? OFFSET ?",mapper,user.id(),size,(long)page*size);
        return new PageResponse<>(rows,page,size,total,(int)((total+size-1)/size),page==0,(long)(page+1)*size>=total);
    }
    @GetMapping("/{id}") @Transactional(readOnly=true)
    public Template get(@PathVariable UUID id){return jdbc.query("SELECT t.*,a.account_number FROM transfer_templates t JOIN accounts a ON a.id=t.destination_account_id WHERE t.id=? AND t.user_id=?",mapper,id,current.get().id()).stream().findFirst().orElseThrow(()->new BusinessException(ErrorCode.TEMPLATE_NOT_FOUND,"Template was not found."));}
    @PostMapping @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Template create(@Valid @RequestBody Create request){
        var user=current.get();
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM accounts WHERE id=? AND customer_id=? AND status='ACTIVE')",Boolean.class,request.sourceAccountId(),user.customerId())))throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND,"Source was not found.");
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM accounts WHERE id=? AND status='ACTIVE')",Boolean.class,request.destinationAccountId())))throw new BusinessException(ErrorCode.ACCOUNT_NOT_FOUND,"Destination was not found.");
        if(request.sourceAccountId().equals(request.destinationAccountId())||new java.math.BigDecimal(request.amount()).signum()<=0)throw new BusinessException(ErrorCode.INVALID_REQUEST,"Invalid template.");
        var id=UUID.randomUUID();jdbc.update("INSERT INTO transfer_templates(id,user_id,name,source_account_id,destination_account_id,amount,description) VALUES (?,?,?,?,?,?,?)",id,user.id(),request.name().strip(),request.sourceAccountId(),request.destinationAccountId(),new java.math.BigDecimal(request.amount()),request.description());
        audit.success(AuditService.Action.TEMPLATE_CREATED,"TRANSFER_TEMPLATE",id,Map.of());return get(id);
    }
    @DeleteMapping("/{id}") @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id){if(jdbc.update("DELETE FROM transfer_templates WHERE id=? AND user_id=?",id,current.get().id())!=1)throw new BusinessException(ErrorCode.TEMPLATE_NOT_FOUND,"Template was not found.");audit.success(AuditService.Action.TEMPLATE_DELETED,"TRANSFER_TEMPLATE",id,Map.of());}
}
