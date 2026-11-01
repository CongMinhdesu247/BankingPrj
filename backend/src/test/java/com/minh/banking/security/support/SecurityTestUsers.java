package com.minh.banking.security.support;

import com.minh.banking.auth.service.JwtService;
import com.minh.banking.security.CurrentUserJwtConverter;
import com.minh.banking.user.enums.RoleCode;
import java.util.*;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** Test-only identities. Requests still pass through the real JWT decoder and authorization. */
public class SecurityTestUsers {
    private final JdbcTemplate jdbc;
    private final JwtService tokens;
    private final JwtDecoder decoder;
    private final CurrentUserJwtConverter converter;
    private final String password = UUID.randomUUID().toString();
    private final String hash;

    public SecurityTestUsers(JdbcTemplate jdbc, JwtService tokens, PasswordEncoder encoder,
            JwtDecoder decoder, CurrentUserJwtConverter converter) {
        this.jdbc = jdbc; this.tokens = tokens; this.hash = encoder.encode(password); this.decoder = decoder; this.converter = converter;
    }

    public String password() { return password; }

    public synchronized UUID ensure(RoleCode role, UUID customerId) {
        if (!"banking_test".equals(jdbc.queryForObject("select current_database()", String.class))) {
            throw new IllegalStateException("Test identities may only write banking_test");
        }
        String username = username(role, customerId);
        var found = jdbc.queryForList("select id from users where username=?", UUID.class, username);
        if (!found.isEmpty()) return found.get(0);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users(id,username,password_hash,customer_id) values (?,?,?,?)", id, username, hash, customerId);
        jdbc.update("insert into user_roles(user_id,role_id) select ?,id from roles where code=?", id, role.name());
        return id;
    }

    public String username(RoleCode role, UUID customerId) {
        return "test." + role.name().toLowerCase(Locale.ROOT) + (customerId == null ? "" : "." + customerId);
    }

    public String bearer(RoleCode role, UUID customerId) { return "Bearer " + tokens.issue(ensure(role, customerId)); }
    public String staff() { return bearer(RoleCode.BANK_STAFF, null); }
    public String customer(UUID accountId) {
        UUID owner = jdbc.queryForObject("select customer_id from accounts where id=?", UUID.class, accountId);
        return bearer(RoleCode.CUSTOMER, owner);
    }

    public void asCustomer(UUID accountId, Runnable action) {
        var old = SecurityContextHolder.getContext();
        var context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(converter.convert(decoder.decode(customer(accountId).substring("Bearer ".length()))));
        SecurityContextHolder.setContext(context);
        try { action.run(); } finally { SecurityContextHolder.setContext(old); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean SecurityTestUsers securityTestUsers(JdbcTemplate jdbc, JwtService tokens, PasswordEncoder encoder,
                JwtDecoder decoder, CurrentUserJwtConverter converter) {
            return new SecurityTestUsers(jdbc, tokens, encoder, decoder, converter);
        }
    }
}
