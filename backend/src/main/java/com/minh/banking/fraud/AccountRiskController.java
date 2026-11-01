package com.minh.banking.fraud;

import com.minh.banking.common.response.PageResponse;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.*;
import org.springframework.web.bind.annotation.*;

/** Account indicators concern outgoing activity only, never guilt by receiving funds. */
@RestController @RequestMapping("/api/v1/staff/account-risk")
@PreAuthorize("hasAnyRole('ADMIN','RISK_OFFICER')")
@Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
public class AccountRiskController {
    private final JdbcTemplate jdbc;
    public AccountRiskController(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    private static final String QUERY = """
        WITH activity AS (
          SELECT t.source_account_id account_id,
            count(*) FILTER (WHERE r.id IS NOT NULL) analyzed,
            count(*) FILTER (WHERE r.id IS NULL) pending,
            count(*) FILTER (WHERE f.status IN ('OPEN','IN_REVIEW')) open_alerts,
            coalesce(max(f.score) FILTER (WHERE f.status IN ('OPEN','IN_REVIEW','CONFIRMED_FRAUD')),0) score,
            max(r.created_at) last_analyzed_at
          FROM transactions t
          LEFT JOIN fraud_analysis_runs r ON r.transaction_id=t.id AND r.analysis_version=1
          LEFT JOIN fraud_alerts f ON f.transaction_id=r.transaction_id AND f.analysis_version=r.analysis_version
          WHERE t.type='INTERNAL_TRANSFER' AND t.created_at>=current_timestamp-interval '30 days'
          GROUP BY t.source_account_id
        ), risks AS (
          SELECT a.id account_id,a.account_number,a.customer_id,c.full_name,c.customer_code,a.status,
            coalesce(x.score,0) score,coalesce(x.analyzed,0) analyzed,coalesce(x.pending,0) pending,
            coalesce(x.open_alerts,0) open_alerts,x.last_analyzed_at,
            CASE WHEN coalesce(x.analyzed,0)=0 THEN 'UNASSESSED'
              WHEN x.score>=80 THEN 'CRITICAL' WHEN x.score>=60 THEN 'HIGH'
              WHEN x.score>=30 THEN 'MEDIUM' ELSE 'LOW' END risk_level
          FROM accounts a JOIN customers c ON c.id=a.customer_id LEFT JOIN activity x ON x.account_id=a.id
        )
        """;
    @GetMapping
    public PageResponse<AccountRisk> list(@RequestParam(defaultValue="0") @Min(0) @Max(1000000) int page,
            @RequestParam(defaultValue="20") @Min(1) @Max(100) int size,
            @RequestParam(defaultValue="") @Size(max=150) String search,
            @RequestParam(defaultValue="") @Pattern(regexp="|UNASSESSED|LOW|MEDIUM|HIGH|CRITICAL") String riskLevel,
            @RequestParam(required=false) UUID accountId) {
        String pattern="%"+search.strip().replace("\\","\\\\").replace("%","\\%").replace("_","\\_")+"%";
        String where=" WHERE (account_number ILIKE ? OR full_name ILIKE ? OR customer_code ILIKE ?) AND (?='' OR risk_level=?)";
        List<Object> args=new ArrayList<>(List.of(pattern,pattern,pattern,riskLevel,riskLevel));
        if (accountId!=null) { where+=" AND account_id=?"; args.add(accountId); }
        long total=jdbc.queryForObject(QUERY+"SELECT count(*) FROM risks"+where,Long.class,args.toArray());
        args.add(size); args.add((long)page*size);
        var content=jdbc.query(QUERY+"SELECT * FROM risks"+where+" ORDER BY score DESC,pending DESC,account_number LIMIT ? OFFSET ?",(rs,n)->
            new AccountRisk(rs.getObject("account_id",UUID.class),rs.getString("account_number"),rs.getObject("customer_id",UUID.class),
                rs.getString("full_name"),rs.getString("customer_code"),rs.getString("status"),rs.getString("risk_level"),
                rs.getInt("score"),rs.getLong("analyzed"),rs.getLong("pending"),rs.getLong("open_alerts"),
                rs.getTimestamp("last_analyzed_at")==null?null:rs.getTimestamp("last_analyzed_at").toInstant()),args.toArray());
        return new PageResponse<>(content,page,size,total,(int)((total+size-1)/size),page==0,(long)(page+1)*size>=total);
    }
    public record AccountRisk(UUID accountId,String accountNumber,UUID customerId,String fullName,String customerCode,
        String status,String riskLevel,int score,long analyzed,long pending,long openAlerts,Instant lastAnalyzedAt) {}
}
