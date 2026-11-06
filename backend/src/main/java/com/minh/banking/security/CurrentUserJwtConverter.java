package com.minh.banking.security;

import com.minh.banking.user.service.UserService;
import java.util.UUID;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

@Component
public class CurrentUserJwtConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    private final UserService users;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public CurrentUserJwtConverter(UserService users, org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.users = users;
        this.jdbc = jdbc;
    }
    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        BankingPrincipal principal;
        try {
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM auth_sessions WHERE id=? AND user_id=? AND revoked_at IS NULL AND expires_at>now())", Boolean.class, UUID.fromString(jwt.getId()), UUID.fromString(jwt.getSubject())))) throw new BadCredentialsException("Session is unavailable");
            principal = users.currentIdentity(UUID.fromString(jwt.getSubject()))
            .orElseThrow(() -> new BadCredentialsException("User is unavailable"));
        } catch (IllegalArgumentException | NullPointerException exception) {
            throw new BadCredentialsException("Invalid subject or session");
        }
        if (principal.isCustomer() && (principal.customerId() == null || principal.roles().size() != 1)) {
            throw new BadCredentialsException("Invalid customer identity");
        }
        var authorities = principal.roles().stream().map(role -> new SimpleGrantedAuthority("ROLE_" + role.name())).toList();
        var authentication = UsernamePasswordAuthenticationToken.authenticated(principal, "", authorities);
        authentication.setDetails(jwt.getId());
        return authentication;
    }
}
