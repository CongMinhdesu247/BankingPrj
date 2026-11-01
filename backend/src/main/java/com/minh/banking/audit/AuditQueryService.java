package com.minh.banking.audit;

import com.minh.banking.common.exception.*;
import com.minh.banking.common.response.PageResponse;
import com.minh.banking.security.CurrentUser;
import com.minh.banking.user.enums.RoleCode;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service @Transactional(readOnly=true)
public class AuditQueryService {
    public enum Outcome { SUCCESS, FAILURE }
    public record AuditLog(UUID id,UUID actorId,String action,String targetType,String targetId,
        Outcome outcome,String requestId,JsonNode details,Instant createdAt) {}
    private final NamedParameterJdbcTemplate jdbc;
    private final CurrentUser current;
    private final JsonMapper json;
    public AuditQueryService(NamedParameterJdbcTemplate jdbc,CurrentUser current,JsonMapper json) {
        this.jdbc=jdbc; this.current=current; this.json=json;
    }
    @PreAuthorize("hasAnyRole('ADMIN','BANK_STAFF')")
    public PageResponse<AuditLog> list(int page,int size,UUID actorId,AuditService.Action action,Outcome outcome,
            Instant from,Instant to) {
        if (from!=null && to!=null && !from.isBefore(to)) throw new BusinessException(ErrorCode.INVALID_TIME_RANGE,"from must precede to.");
        var principal=current.get();
        var params=new MapSqlParameterSource();
        StringBuilder where=new StringBuilder(" WHERE 1=1");
        if (!principal.roles().contains(RoleCode.ADMIN)) {
            where.append(" AND actor_id=:staff"); params.addValue("staff",principal.id());
        }
        if (actorId!=null) { where.append(" AND actor_id=:actor"); params.addValue("actor",actorId); }
        if (action!=null) { where.append(" AND action=:action"); params.addValue("action",action.name()); }
        if (outcome!=null) { where.append(" AND outcome=:outcome"); params.addValue("outcome",outcome.name()); }
        if (from!=null) { where.append(" AND created_at>=:from"); params.addValue("from",Timestamp.from(from)); }
        if (to!=null) { where.append(" AND created_at<:to"); params.addValue("to",Timestamp.from(to)); }
        long total=jdbc.queryForObject("SELECT count(*) FROM audit_logs"+where,params,Long.class);
        params.addValue("size",size).addValue("offset",(long)page*size);
        var rows=jdbc.query("SELECT * FROM audit_logs"+where+" ORDER BY created_at DESC,id DESC LIMIT :size OFFSET :offset",params,
            (rs,n)->new AuditLog(rs.getObject("id",UUID.class),rs.getObject("actor_id",UUID.class),rs.getString("action"),
                rs.getString("target_type"),rs.getString("target_id"),Outcome.valueOf(rs.getString("outcome")),
                rs.getString("request_id"),json.readTree(rs.getString("details")),rs.getTimestamp("created_at").toInstant()));
        return PageResponse.from(new PageImpl<>(rows,PageRequest.of(page,size),total));
    }
}
