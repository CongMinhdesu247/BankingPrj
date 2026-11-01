package com.minh.banking.auth.service;

import com.minh.banking.auth.dto.*;
import com.minh.banking.common.exception.*;
import com.minh.banking.user.repository.UserRepository;
import com.minh.banking.user.service.UserService;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthVerificationService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JwtService tokens;
    private final String dummyHash;
    private final SecurityCenterService security;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public AuthVerificationService(UserRepository users,PasswordEncoder passwords,JwtService tokens,SecurityCenterService security,org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.security=security;this.jdbc=jdbc;
        this.users=users; this.passwords=passwords; this.tokens=tokens;
        dummyHash=passwords.encode(UUID.randomUUID().toString());
    }
    @Transactional
    public TokenResponse verify(LoginRequest request) {
        jdbc.queryForList("SELECT id FROM users WHERE username=? FOR UPDATE",UUID.class,request.username());
        var found=users.findWithRolesByUsername(request.username());
        boolean matches=passwords.matches(request.password(),found.map(user->user.getPasswordHash()).orElse(dummyHash));
        if (!matches || found.isEmpty() || !found.get().isEnabled() || found.get().getRoles().isEmpty())
            throw new BusinessException(ErrorCode.INVALID_CREDENTIALS,"Username or password is invalid.");
        var user=found.get();
        security.verifyOtp(user.getId(),request.otp(),true);
        return new TokenResponse(tokens.issue(user.getId()),"Bearer",tokens.ttlSeconds(),UserService.toResponse(user));
    }
}
