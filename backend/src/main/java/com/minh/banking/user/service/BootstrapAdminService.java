package com.minh.banking.user.service;

import com.minh.banking.user.entity.User;
import com.minh.banking.user.enums.RoleCode;
import com.minh.banking.user.repository.*;
import jakarta.persistence.EntityManager;
import java.util.*;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Profile("dev")
public class BootstrapAdminService {
    private final UserRepository users;
    private final RoleRepository roles;
    private final PasswordEncoder passwords;
    private final EntityManager entityManager;
    public BootstrapAdminService(UserRepository users, RoleRepository roles, PasswordEncoder passwords, EntityManager entityManager) {
        this.users = users; this.roles = roles; this.passwords = passwords; this.entityManager = entityManager;
    }
    @Transactional
    public boolean seed(String username, String password) {
        entityManager.createNativeQuery("select 1 from pg_advisory_xact_lock(78291234)").getSingleResult();
        if (users.count() != 0) return false;
        String normalized = username.strip().toLowerCase(Locale.ROOT);
        if (!normalized.matches("[a-z0-9][a-z0-9._-]{2,99}") || password == null || password.isBlank()
                || password.length() < 12 || password.length() > 128) {
            throw new IllegalStateException("Configure a bootstrap username and password (12..128 characters)");
        }
        var adminRole = roles.findAllByCodeIn(Set.of(RoleCode.ADMIN));
        if (adminRole.size() != 1) throw new IllegalStateException("ADMIN role is not configured");
        users.saveAndFlush(new User(normalized, passwords.encode(password), null, new HashSet<>(adminRole)));
        return true;
    }
}
