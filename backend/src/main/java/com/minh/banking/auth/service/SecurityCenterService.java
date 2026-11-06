package com.minh.banking.auth.service;

import com.minh.banking.audit.AuditService;
import com.minh.banking.common.exception.*;
import com.minh.banking.security.CurrentUser;
import jakarta.validation.constraints.*;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service @Transactional
public class SecurityCenterService {
    private final JdbcTemplate jdbc;
    private final CurrentUser current;
    private final PasswordEncoder passwords;
    private final Totp totp;
    private final LoginAttemptService attempts;
    private final AuditService audit;
    private final Clock clock;
    public SecurityCenterService(JdbcTemplate jdbc, CurrentUser current, PasswordEncoder passwords, Totp totp, LoginAttemptService attempts, AuditService audit, Clock clock) {
        this.jdbc = jdbc;
        this.current = current;
        this.passwords = passwords;
        this.totp = totp;
        this.attempts = attempts;
        this.audit = audit;
        this.clock = clock;
    }
    public record Proof(@NotBlank @Size(max=128) String password, @Size(max=80) String otp) {
        @Override public String toString() {
            return "Proof[REDACTED]";
        }
    }
    public record PasswordChange(@NotBlank @Size(max=128) String password, @NotBlank @Size(min=12, max=128) String newPassword, @Size(max=80) String otp) {
        @Override public String toString() {
            return "PasswordChange[REDACTED]";
        }
    }
    public record Session(UUID id, String deviceLabel, Instant createdAt, Instant expiresAt, boolean current) {}
    public UUID sessionId() {
        return UUID.fromString((String)SecurityContextHolder.getContext().getAuthentication().getDetails());
    }
    public void lockUser(UUID id) {
        jdbc.queryForObject("SELECT id FROM users WHERE id=? FOR UPDATE", UUID.class, id);
    }
    /** Called while the user row is locked. Successful OTPs/recovery codes are consumed atomically. */
    public void verifyOtp(UUID id, String otp, boolean login) {
        var settings = jdbc.queryForList("SELECT * FROM user_security WHERE user_id=?", id);
        if (settings.isEmpty() || !Boolean.TRUE.equals(settings.getFirst().get("totp_enabled"))) return;
        if (otp == null || otp.isBlank()) throw new BusinessException(login?ErrorCode.MFA_REQUIRED:ErrorCode.SECURITY_VERIFICATION_FAILED, "Second factor is required.");
        var row = settings.getFirst();
        long step = totp.match(totp.decrypt(id, (String)row.get("totp_secret")), otp, clock.instant().getEpochSecond(), ((Number)row.get("last_totp_step")).longValue());
        if (step >= 0) {
            jdbc.update("UPDATE user_security SET last_totp_step=? WHERE user_id=?", step, id);
            return;
        }
        if (jdbc.update("DELETE FROM recovery_codes WHERE user_id=? AND code_hash=?", id, Totp.hash(otp.strip())) == 1) return;
        throw new BusinessException(login?ErrorCode.INVALID_CREDENTIALS:ErrorCode.SECURITY_VERIFICATION_FAILED, "Invalid verification code.");
    }
    public void prove(Proof proof) {
        var user = current.get();
        attempts.check(user.username());
        var hash = jdbc.queryForObject("SELECT password_hash FROM users WHERE id=?", String.class, user.id());
        if (!passwords.matches(proof.password(), hash)) throw new BusinessException(ErrorCode.SECURITY_VERIFICATION_FAILED, "Verification failed.");
        lockUser(user.id());
        verifyOtp(user.id(), proof.otp(), false);
        attempts.succeeded(user.username());
    }
    public Map<String, Object> settings() {
        boolean enabled = Boolean.TRUE.equals(jdbc.queryForObject("SELECT coalesce((SELECT totp_enabled FROM user_security WHERE user_id=?),false)", Boolean.class, current.get().id()));
        return Map.of("totpEnabled", enabled, "recoveryCodesRemaining", jdbc.queryForObject("SELECT count(*) FROM recovery_codes WHERE user_id=?", Long.class, current.get().id()));
    }
    public List<Session> sessions() {
        var sid = sessionId();
        return jdbc.query("SELECT * FROM auth_sessions WHERE user_id=? AND revoked_at IS NULL AND expires_at>now() ORDER BY created_at DESC LIMIT 100",
            (rs, n) -> new Session(rs.getObject("id", UUID.class), rs.getString("device_label"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(), sid.equals(rs.getObject("id", UUID.class))), current.get().id());
    }
    public void revoke(UUID id) {
        var user = current.get();
        lockUser(user.id());
        if (jdbc.update("UPDATE auth_sessions SET revoked_at=now() WHERE id=? AND user_id=? AND revoked_at IS NULL", id, user.id()) == 0)
        throw new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "Session was not found.");
        audit.success(AuditService.Action.SESSION_REVOKED, "SESSION", id, Map.of());
    }
    public void revokeOthers() {
        var user = current.get();
        lockUser(user.id());
        jdbc.update("UPDATE auth_sessions SET revoked_at=now() WHERE user_id=? AND id<>? AND revoked_at IS NULL", user.id(), sessionId());
        audit.success(AuditService.Action.SESSION_REVOKED, "USER", user.id(), Map.of("scope", "OTHER_SESSIONS"));
    }
    public void changePassword(PasswordChange request) {
        prove(new Proof(request.password(), request.otp()));
        if (request.password().equals(request.newPassword())) throw new BusinessException(ErrorCode.INVALID_REQUEST, "New password must differ.");
        UUID id = current.get().id();
        jdbc.update("UPDATE users SET password_hash=?,updated_at=now() WHERE id=?", passwords.encode(request.newPassword()), id);
        jdbc.update("UPDATE auth_sessions SET revoked_at=now() WHERE user_id=? AND revoked_at IS NULL", id);
        jdbc.update("UPDATE password_reset_tokens SET used_at=now() WHERE user_id=? AND used_at IS NULL", id);
        audit.success(AuditService.Action.PASSWORD_CHANGED, "USER", id, Map.of());
    }
    public Map<String, String> setup(Proof proof) {
        prove(proof);
        var user = current.get();
        if (Boolean.TRUE.equals(settings().get("totpEnabled"))) throw new BusinessException(ErrorCode.INVALID_WORKFLOW_STATE, "Second factor is already enabled.");
        String secret = totp.newSecret();
        jdbc.update("INSERT INTO user_security(user_id,pending_secret,pending_expires_at) VALUES (?,?,now()+interval '10 minutes') ON CONFLICT(user_id) DO UPDATE SET pending_secret=EXCLUDED.pending_secret,pending_expires_at=EXCLUDED.pending_expires_at", user.id(), totp.encrypt(user.id(), secret));
        return Map.of("secret", secret, "uri", "otpauth://totp/Banking:"+java.net.URLEncoder.encode(user.username(), java.nio.charset.StandardCharsets.UTF_8)+"?secret="+secret+"&issuer=Banking&algorithm=SHA1&digits=6&period=30");
    }
    public List<String> enable(String code) {
        var user = current.get();
        attempts.check(user.username());
        lockUser(user.id());
        var rows = jdbc.queryForList("SELECT pending_secret FROM user_security WHERE user_id=? AND NOT totp_enabled AND pending_expires_at>now()", user.id());
        long step = rows.isEmpty()?-1:totp.match(totp.decrypt(user.id(), (String)rows.getFirst().get("pending_secret")), code, clock.instant().getEpochSecond(), -1);
        if (step<0) throw new BusinessException(ErrorCode.SECURITY_VERIFICATION_FAILED, "Setup expired or code is invalid.");
        jdbc.update("UPDATE user_security SET totp_secret=pending_secret,totp_enabled=true,last_totp_step=?,pending_secret=NULL,pending_expires_at=NULL WHERE user_id=?", step, user.id());
        jdbc.update("DELETE FROM recovery_codes WHERE user_id=?", user.id());
        var codes = new ArrayList<String>();
        for (int i=0; i<8; i++) {
            String value = UUID.randomUUID().toString();
            codes.add(value);
            jdbc.update("INSERT INTO recovery_codes(user_id,code_hash) VALUES (?,?)", user.id(), Totp.hash(value));
        }
        revokeOthers();
        attempts.succeeded(user.username());
        audit.success(AuditService.Action.MFA_ENABLED, "USER", user.id(), Map.of());
        return codes;
    }
    public void disable(Proof proof) {
        prove(proof);
        var id = current.get().id();
        jdbc.update("UPDATE user_security SET totp_enabled=false,totp_secret=NULL,pending_secret=NULL,pending_expires_at=NULL,last_totp_step=-1 WHERE user_id=?", id);
        jdbc.update("DELETE FROM recovery_codes WHERE user_id=?", id);
        revokeOthers();
        audit.success(AuditService.Action.MFA_DISABLED, "USER", id, Map.of());
    }
}
