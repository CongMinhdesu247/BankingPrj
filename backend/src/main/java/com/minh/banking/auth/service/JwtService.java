package com.minh.banking.auth.service;

import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class JwtService {
    private final JwtEncoder encoder;
    private final String issuer;
    private final String audience;
    private final long ttlSeconds;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public JwtService(JwtEncoder encoder, @Value("${banking.security.jwt.issuer}") String issuer,
            @Value("${banking.security.jwt.audience}") String audience,
            @Value("${banking.security.jwt.ttl-seconds:900}") long ttlSeconds,org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.jdbc=jdbc;
        if (ttlSeconds < 1 || ttlSeconds > 3600) throw new IllegalArgumentException("JWT TTL must be 1..3600 seconds");
        this.encoder = encoder; this.issuer = issuer; this.audience = audience; this.ttlSeconds = ttlSeconds;
    }
    @org.springframework.transaction.annotation.Transactional
    public String issue(UUID userId) {
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE",UUID.class,userId);
        Instant now = Instant.now();
        UUID id=UUID.randomUUID();
        String label="Unknown device";
        if(org.springframework.web.context.request.RequestContextHolder.getRequestAttributes() instanceof org.springframework.web.context.request.ServletRequestAttributes attributes){
            var agent=attributes.getRequest().getHeader("User-Agent");if(agent!=null)label=agent.replaceAll("[\\p{Cntrl}]","").substring(0,Math.min(200,agent.replaceAll("[\\p{Cntrl}]","").length()));
        }
        jdbc.update("INSERT INTO auth_sessions(id,user_id,created_at,expires_at,device_label) VALUES (?,?,?,?,?)",id,userId,java.sql.Timestamp.from(now),java.sql.Timestamp.from(now.plusSeconds(ttlSeconds)),label);
        var claims = JwtClaimsSet.builder().issuer(issuer).subject(userId.toString()).id(id.toString())
            .audience(java.util.List.of(audience)).issuedAt(now).notBefore(now).expiresAt(now.plusSeconds(ttlSeconds)).build();
        return encoder.encode(JwtEncoderParameters.from(JwsHeader.with(MacAlgorithm.HS256).build(), claims)).getTokenValue();
    }
    public long ttlSeconds() { return ttlSeconds; }
}
