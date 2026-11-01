package com.minh.banking.notification;

import com.minh.banking.common.exception.*;
import com.minh.banking.common.response.PageResponse;
import com.minh.banking.security.CurrentUser;
import java.time.*;
import java.sql.Timestamp;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service @Transactional(readOnly=true)
public class NotificationService {
    public record Notification(UUID id,UUID eventId,String type,JsonNode content,String status,Instant readAt,Instant createdAt) {}
    public record Summary(long unreadCount,Notification latest) {}
    private final JdbcTemplate jdbc;
    private final JsonMapper json;
    private final CurrentUser current;
    private final Clock clock;
    public NotificationService(JdbcTemplate jdbc,JsonMapper json,CurrentUser current,Clock clock) { this.jdbc=jdbc; this.json=json; this.current=current; this.clock=clock; }
    @Transactional(propagation=Propagation.MANDATORY)
    public void create(UUID recipient,UUID businessEvent,String type,Map<String,?> content) {
        if (recipient==null) throw new IllegalStateException("Notification recipient missing in committed evidence");
        jdbc.update("""
            INSERT INTO notifications(recipient_id,event_id,type,content,created_at) VALUES (?,?,?,cast(? as jsonb),?)
            ON CONFLICT(recipient_id,event_id,type) DO NOTHING
            """,recipient,businessEvent,type,json.writeValueAsString(content),Timestamp.from(clock.instant()));
    }
    @PreAuthorize("isAuthenticated()")
    public PageResponse<Notification> list(int page,int size,boolean unreadOnly) {
        String where=" WHERE recipient_id=?"+(unreadOnly?" AND status='UNREAD'":"");
        UUID owner=current.get().id();
        long total=jdbc.queryForObject("SELECT count(*) FROM notifications"+where,Long.class,owner);
        var rows=jdbc.query("SELECT * FROM notifications"+where+" ORDER BY created_at DESC,id DESC LIMIT ? OFFSET ?",(rs,n)->{
            var read=rs.getTimestamp("read_at");
            return new Notification(rs.getObject("id",UUID.class),rs.getObject("event_id",UUID.class),rs.getString("type"),
                json.readTree(rs.getString("content")),rs.getString("status"),read==null?null:read.toInstant(),rs.getTimestamp("created_at").toInstant());
        },owner,size,(long)page*size);
        return PageResponse.from(new PageImpl<>(rows,PageRequest.of(page,size),total));
    }
    @Transactional @PreAuthorize("isAuthenticated()")
    public void read(UUID id) {
        int found=jdbc.update("UPDATE notifications SET status='READ',read_at=coalesce(read_at,?) WHERE id=? AND recipient_id=?",
            Timestamp.from(clock.instant()),id,current.get().id());
        if (found!=1) throw new BusinessException(ErrorCode.NOTIFICATION_NOT_FOUND,"Notification was not found.");
    }
    @Transactional(readOnly=true,isolation=Isolation.REPEATABLE_READ) @PreAuthorize("isAuthenticated()")
    public Summary summary() {
        UUID owner=current.get().id();
        long unread=jdbc.queryForObject("SELECT count(*) FROM notifications WHERE recipient_id=? AND status='UNREAD'",Long.class,owner);
        var rows=jdbc.query("SELECT * FROM notifications WHERE recipient_id=? ORDER BY created_at DESC,id DESC LIMIT 1",(rs,n)->{
            var read=rs.getTimestamp("read_at");
            return new Notification(rs.getObject("id",UUID.class),rs.getObject("event_id",UUID.class),rs.getString("type"),json.readTree(rs.getString("content")),rs.getString("status"),read==null?null:read.toInstant(),rs.getTimestamp("created_at").toInstant());
        },owner);
        return new Summary(unread,rows.isEmpty()?null:rows.getFirst());
    }
}
