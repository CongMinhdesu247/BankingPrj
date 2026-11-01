package com.minh.banking.auth.controller;

import com.minh.banking.audit.AuditService;
import com.minh.banking.auth.service.*;
import com.minh.banking.common.exception.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** Development mailbox is opt-in and restricted to a loopback-bound server. No token is returned by API. */
@RestController @RequestMapping("/api/v1/auth")
public class PasswordRecoveryController {
    private final JdbcTemplate jdbc;private final PasswordEncoder passwords;private final SecurityCenterService security;
    private final AuditService audit;private final boolean localMailbox;private final LoginAttemptService attempts;
    public PasswordRecoveryController(JdbcTemplate jdbc,PasswordEncoder passwords,SecurityCenterService security,AuditService audit,LoginAttemptService attempts,
            @Value("${banking.security.local-reset-mailbox-enabled:false}") boolean enabled,@Value("${server.address:127.0.0.1}") String address) {
        this.jdbc=jdbc;this.passwords=passwords;this.security=security;this.audit=audit;this.attempts=attempts;
        if(enabled&&!Set.of("127.0.0.1","localhost","::1").contains(address))throw new IllegalStateException("Local recovery mailbox requires loopback binding");localMailbox=enabled;
    }
    public record Forgot(@NotBlank @Size(max=100) String username){}
    public record RecoveryOptions(String deliveryMode,String localMailboxPath){}
    @GetMapping("/recovery-options")
    public RecoveryOptions options(){
        return new RecoveryOptions(localMailbox?"LOCAL_MAILBOX":"UNAVAILABLE",localMailbox?Path.of("target","local-mailbox").toAbsolutePath().normalize().toString():null);
    }
    public record Reset(@NotBlank @Size(max=100) String token,@NotBlank @Size(min=12,max=128) String newPassword,@Size(max=80) String otp){
        @Override public String toString(){return "Reset[REDACTED]";}
    }
    @PostMapping("/forgot-password") @Transactional
    public Map<String,String> forgot(@Valid @RequestBody Forgot request) {
        if(localMailbox){
            var found=jdbc.queryForList("SELECT id FROM users WHERE username=? AND enabled=true",UUID.class,request.username().strip().toLowerCase(Locale.ROOT));
            if(!found.isEmpty()){
                UUID user=found.getFirst();security.lockUser(user);
                String token=UUID.randomUUID().toString()+UUID.randomUUID();
                jdbc.update("UPDATE password_reset_tokens SET used_at=now() WHERE user_id=? AND used_at IS NULL",user);
                jdbc.update("INSERT INTO password_reset_tokens(token_hash,user_id,expires_at) VALUES (?,?,now()+interval '15 minutes')",Totp.hash(token),user);
                try {var directory=Path.of("target","local-mailbox");Files.createDirectories(directory);
                    Files.writeString(directory.resolve("reset-"+UUID.randomUUID()+".txt"),"LOCAL DEVELOPMENT ONLY\nUsername: "+request.username().strip()+"\nExpires in 15 minutes\nhttp://127.0.0.1:5173/reset-password#"+token+"\n");}
                catch(java.io.IOException error){throw new IllegalStateException("Local mailbox is unavailable",error);}
            }
        }
        return Map.of("message","Nếu tài khoản tồn tại, hướng dẫn khôi phục sẽ được gửi qua kênh hỗ trợ đã cấu hình.");
    }
    @PostMapping("/reset-password") @Transactional @ResponseStatus(org.springframework.http.HttpStatus.NO_CONTENT)
    public void reset(@Valid @RequestBody Reset request) {
        String hash=Totp.hash(request.token());
        var found=jdbc.queryForList("SELECT user_id FROM password_reset_tokens WHERE token_hash=? AND used_at IS NULL AND expires_at>now()",UUID.class,hash);
        if(found.isEmpty())throw invalid();UUID user=found.getFirst();security.lockUser(user);
        if(jdbc.update("UPDATE password_reset_tokens SET used_at=now() WHERE token_hash=? AND used_at IS NULL AND expires_at>now()",hash)!=1)throw invalid();
        if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT enabled FROM users WHERE id=?",Boolean.class,user)))throw invalid();
        String username=jdbc.queryForObject("SELECT username FROM users WHERE id=?",String.class,user);
        attempts.check(username);security.verifyOtp(user,request.otp(),false);
        jdbc.update("UPDATE users SET password_hash=?,updated_at=now() WHERE id=?",passwords.encode(request.newPassword()),user);
        jdbc.update("UPDATE auth_sessions SET revoked_at=now() WHERE user_id=? AND revoked_at IS NULL",user);
        jdbc.update("UPDATE password_reset_tokens SET used_at=now() WHERE user_id=? AND used_at IS NULL",user);
        audit.write(user,AuditService.Action.PASSWORD_RESET,"USER",user,true,Map.of());attempts.succeeded(username);
    }
    private BusinessException invalid(){return new BusinessException(ErrorCode.INVALID_RESET_TOKEN,"Reset link expired or is invalid.");}
}
