package com.minh.banking.support.chat;

import com.minh.banking.security.CurrentUser;
import com.minh.banking.redis.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me/chatbot")
@PreAuthorize("hasRole('CUSTOMER')")
public class ChatbotController {
    private final ChatbotService chatbot;
    private final CurrentUser current;
    private final RedisStateStore store;
    private final RedisKeyFactory keys;
    public ChatbotController(ChatbotService chatbot, CurrentUser current, RedisStateStore store, RedisKeyFactory keys) {
        this.chatbot=chatbot; this.current=current; this.store=store; this.keys=keys;
    }
    @GetMapping
    public ResponseEntity<Status> status() {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new Status(chatbot.enabled()));
    }
    @PostMapping
    public ResponseEntity<ChatbotService.Answer> chat(@Valid @RequestBody Question question) {
        checkQuota(current.get().id().toString(), 5);
        if (chatbot.enabled()) checkQuota("shared-provider", 20);
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(chatbot.answer(question));
    }
    private void checkQuota(String actor, int limit) {
        long wait;
        try { wait=store.consume(keys.rate("chatbot", actor), 60, limit); }
        catch (RuntimeException failure) { throw ThrottleException.unavailable(); }
        if (wait>0) throw ThrottleException.limited(wait);
    }
    public record Status(boolean aiEnabled) {}
    public record Message(@NotNull @Pattern(regexp="user|model") String role,
                          @NotBlank @Size(max=2000) String text) {}
    public record Question(@NotBlank @Size(max=2000) String message,
                           @NotNull @Size(max=10) List<@NotNull @Valid Message> history,
                           @AssertTrue boolean consent) {}
}
