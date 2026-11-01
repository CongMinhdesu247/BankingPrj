package com.minh.banking.user.service;

import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional(readOnly=true)
public class NotificationRecipientService {
    private final JdbcTemplate jdbc;
    public NotificationRecipientService(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    public List<UUID> riskOfficers() {
        return jdbc.queryForList("""
            SELECT u.id FROM users u WHERE u.enabled AND EXISTS
            (SELECT 1 FROM user_roles ur JOIN roles r ON r.id=ur.role_id WHERE ur.user_id=u.id AND r.code='RISK_OFFICER')
            ORDER BY u.id
            """,UUID.class);
    }
}
