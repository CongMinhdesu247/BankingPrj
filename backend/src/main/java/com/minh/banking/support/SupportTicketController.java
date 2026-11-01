package com.minh.banking.support;

import com.minh.banking.audit.AuditService;
import com.minh.banking.common.exception.*;
import com.minh.banking.common.response.PageResponse;
import com.minh.banking.security.CurrentUser;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/v1") @Transactional
public class SupportTicketController {
    private final JdbcTemplate jdbc;private final CurrentUser current;private final AuditService audit;
    private final com.minh.banking.notification.NotificationService notifications;
    public SupportTicketController(JdbcTemplate jdbc,CurrentUser current,AuditService audit,com.minh.banking.notification.NotificationService notifications){this.jdbc=jdbc;this.current=current;this.audit=audit;this.notifications=notifications;}
    public enum Category {WRONG_RECIPIENT,UNRECOGNIZED,OTHER}
    public record Create(@NotNull UUID transactionId,@NotNull Category category,@NotBlank @Size(max=120) String subject,@NotBlank @Size(max=2000) String message){}
    public record Reply(@NotBlank @Size(max=2000) String message){}
    public record Update(@NotNull @Min(0) Long expectedVersion,@NotBlank @Pattern(regexp="IN_PROGRESS|RESOLVED|CLOSED") String status,@Size(max=2000) String message){}
    public record Ticket(UUID id,UUID transactionId,String reference,String username,String subject,String category,String status,String assignedUsername,long version,Instant createdAt,Instant updatedAt){}
    public record Message(UUID id,String author,String body,Instant createdAt){}
    public record Detail(Ticket ticket,List<Message> messages){}
    private static final String SELECT="SELECT s.*,t.reference,u.username,a.username assigned_username FROM support_tickets s JOIN transactions t ON t.id=s.transaction_id JOIN users u ON u.id=s.user_id LEFT JOIN users a ON a.id=s.assigned_to";
    private final org.springframework.jdbc.core.RowMapper<Ticket> mapper=(rs,n)->new Ticket(rs.getObject("id",UUID.class),rs.getObject("transaction_id",UUID.class),rs.getString("reference"),rs.getString("username"),rs.getString("subject"),rs.getString("category"),rs.getString("status"),rs.getString("assigned_username"),rs.getLong("version"),rs.getTimestamp("created_at").toInstant(),rs.getTimestamp("updated_at").toInstant());
    private boolean staff(){return current.get().roles().stream().anyMatch(r->r.name().equals("ADMIN")||r.name().equals("BANK_STAFF"));}
    private Ticket require(UUID id,boolean lock){
        var args=new ArrayList<Object>();args.add(id);String where=" WHERE s.id=?";
        if(!staff()){where+=" AND s.user_id=?";args.add(current.get().id());}
        return jdbc.query(SELECT+where+(lock?" FOR UPDATE OF s":""),mapper,args.toArray()).stream().findFirst().orElseThrow(()->new BusinessException(ErrorCode.TICKET_NOT_FOUND,"Ticket was not found."));
    }
    @GetMapping({"/me/support-tickets","/staff/support-tickets"}) @PreAuthorize("hasAnyRole('CUSTOMER','BANK_STAFF','ADMIN')")
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ)
    public PageResponse<Ticket> list(@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,@RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue="") @Pattern(regexp="|OPEN|IN_PROGRESS|RESOLVED|CLOSED") String status){
        String where=" WHERE (?='' OR s.status=?)";var args=new ArrayList<Object>(List.of(status,status));
        if(!staff()){where+=" AND s.user_id=?";args.add(current.get().id());}
        long total=jdbc.queryForObject("SELECT count(*) FROM support_tickets s"+where,Long.class,args.toArray());args.add(size);args.add((long)page*size);
        var rows=jdbc.query(SELECT+where+" ORDER BY s.updated_at DESC,s.id DESC LIMIT ? OFFSET ?",mapper,args.toArray());return new PageResponse<>(rows,page,size,total,(int)((total+size-1)/size),page==0,(long)(page+1)*size>=total);
    }
    @GetMapping({"/me/support-tickets/{id}","/staff/support-tickets/{id}"}) @PreAuthorize("hasAnyRole('CUSTOMER','BANK_STAFF','ADMIN')") @Transactional(readOnly=true)
    public Detail detail(@PathVariable UUID id){var ticket=require(id,false);return new Detail(ticket,jdbc.query("SELECT m.*,u.username FROM support_messages m JOIN users u ON u.id=m.author_id WHERE m.ticket_id=? ORDER BY m.created_at,m.id",(rs,n)->new Message(rs.getObject("id",UUID.class),rs.getString("username"),rs.getString("body"),rs.getTimestamp("created_at").toInstant()),id));}
    @PostMapping("/me/support-tickets") @PreAuthorize("hasRole('CUSTOMER')") @ResponseStatus(org.springframework.http.HttpStatus.CREATED)
    public Detail create(@Valid @RequestBody Create request){
        var user=current.get();
        boolean owned=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM transactions t JOIN accounts a ON a.id=t.source_account_id OR a.id=t.destination_account_id WHERE t.id=? AND t.type='INTERNAL_TRANSFER' AND a.customer_id=?)",Boolean.class,request.transactionId(),user.customerId()));
        if(!owned)throw new BusinessException(ErrorCode.TRANSACTION_NOT_FOUND,"Transaction was not found.");
        var id=UUID.randomUUID();jdbc.update("INSERT INTO support_tickets(id,user_id,transaction_id,category,subject) VALUES (?,?,?,?,?)",id,user.id(),request.transactionId(),request.category().name(),request.subject().strip());message(id,request.message());audit.success(AuditService.Action.TICKET_CREATED,"SUPPORT_TICKET",id,Map.of("transactionId",request.transactionId()));return detail(id);
    }
    @PostMapping({"/me/support-tickets/{id}/messages","/staff/support-tickets/{id}/messages"}) @PreAuthorize("hasAnyRole('CUSTOMER','BANK_STAFF','ADMIN')")
    public Detail reply(@PathVariable UUID id,@Valid @RequestBody Reply request){var ticket=require(id,true);if(ticket.status().equals("CLOSED"))throw state();
        String nextStatus=!staff()&&ticket.status().equals("RESOLVED")?"OPEN":ticket.status();
        message(id,request.message());jdbc.update("UPDATE support_tickets SET status=?,version=version+1,updated_at=now() WHERE id=?",nextStatus,id);
        notifyOwner(ticket,nextStatus,request.message());audit.success(AuditService.Action.TICKET_REPLIED,"SUPPORT_TICKET",id,Map.of());return detail(id);}
    @PatchMapping({"/me/support-tickets/{id}","/staff/support-tickets/{id}"}) @PreAuthorize("hasAnyRole('CUSTOMER','BANK_STAFF','ADMIN')")
    public Detail update(@PathVariable UUID id,@Valid @RequestBody Update request){var ticket=require(id,true);if(ticket.version()!=request.expectedVersion()||ticket.status().equals("CLOSED"))throw state();
        if(!staff()&&(!request.status().equals("CLOSED")||!ticket.status().equals("RESOLVED")))throw new BusinessException(ErrorCode.FORBIDDEN,"Only a resolved ticket can be closed by its owner.");
        if(staff()&&request.status().equals("CLOSED"))throw state();
        if(staff()&&request.status().equals("RESOLVED")&&(request.message()==null||request.message().isBlank()))throw new BusinessException(ErrorCode.INVALID_REQUEST,"Resolution message is required.");
        if(request.message()!=null&&!request.message().isBlank())message(id,request.message());
        jdbc.update("UPDATE support_tickets SET status=?,assigned_to=CASE WHEN ? THEN ? ELSE assigned_to END,version=version+1,updated_at=now() WHERE id=?",request.status(),staff(),current.get().id(),id);
        notifyOwner(ticket,request.status(),request.message());
        audit.success(AuditService.Action.TICKET_UPDATED,"SUPPORT_TICKET",id,Map.of("status",request.status()));return detail(id);}
    /** Persist the inbox item in the same transaction as the locked ticket mutation. */
    private void notifyOwner(Ticket before,String nextStatus,String body){
        if(!staff())return;
        UUID owner=jdbc.queryForObject("SELECT user_id FROM support_tickets WHERE id=?",UUID.class,before.id());
        if(owner.equals(current.get().id()))return;
        boolean changed=!before.status().equals(nextStatus),hasMessage=body!=null&&!body.isBlank();
        if(!changed&&!hasMessage)return;
        long version=before.version()+1;
        UUID event=UUID.nameUUIDFromBytes(("support-ticket:"+before.id()+":"+version).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var content=new HashMap<String,Object>();content.put("ticketId",before.id());content.put("subject",before.subject());content.put("ticketStatus",nextStatus);content.put("previousStatus",before.status());content.put("sender",current.get().username());
        if(hasMessage){String value=body.strip();content.put("messagePreview",value.length()>180?value.substring(0,180)+"…":value);}
        notifications.create(owner,event,changed?"SUPPORT_STATUS_CHANGED":"SUPPORT_REPLY",content);
    }
    private void message(UUID id,String body){jdbc.update("INSERT INTO support_messages(ticket_id,author_id,body) VALUES (?,?,?)",id,current.get().id(),body.strip());}
    private BusinessException state(){return new BusinessException(ErrorCode.INVALID_WORKFLOW_STATE,"Ticket changed or the requested transition is unavailable.");}
}
