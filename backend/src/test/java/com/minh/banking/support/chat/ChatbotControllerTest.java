package com.minh.banking.support.chat;

import com.minh.banking.redis.*;
import com.minh.banking.security.*;
import com.minh.banking.user.enums.RoleCode;
import java.util.*;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import org.springframework.context.annotation.*;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

class ChatbotControllerTest {
    @Configuration @EnableMethodSecurity static class Config {
        @Bean ChatbotService service() { return mock(ChatbotService.class); }
        @Bean CurrentUser current() { return new CurrentUser(); }
        @Bean RedisStateStore store() { return mock(RedisStateStore.class); }
        @Bean RedisKeyFactory keys() { return mock(RedisKeyFactory.class); }
        @Bean ChatbotController controller(ChatbotService service,CurrentUser current,RedisStateStore store,RedisKeyFactory keys) {
            return new ChatbotController(service,current,store,keys);
        }
    }
    private AnnotationConfigApplicationContext context;
    private ChatbotController controller; private ChatbotService service; private RedisStateStore store;
    @BeforeEach void setup() {
        context=new AnnotationConfigApplicationContext(Config.class); controller=context.getBean(ChatbotController.class);
        service=context.getBean(ChatbotService.class); store=context.getBean(RedisStateStore.class);
        when(context.getBean(RedisKeyFactory.class).rate(eq("chatbot"),anyString())).thenAnswer(i->"chat:"+i.getArgument(1));
    }
    @AfterEach void cleanup() { SecurityContextHolder.clearContext(); context.close(); }
    private void login(RoleCode role) {
        var user=new BankingPrincipal(UUID.randomUUID(),"user",UUID.randomUUID(),Set.of(role));
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(user,"",List.of(new SimpleGrantedAuthority("ROLE_"+role.name()))));
    }
    private ChatbotController.Question question() { return new ChatbotController.Question("Cần hướng dẫn",List.of(),true); }
    @Test void unauthenticatedAndStaffCannotAccessChat() {
        assertThrows(org.springframework.security.core.AuthenticationException.class,()->controller.status());
        login(RoleCode.BANK_STAFF);
        assertThrows(org.springframework.security.access.AccessDeniedException.class,()->controller.chat(question()));
        verifyNoInteractions(service,store);
    }
    @Test void customerGetsNoStoreResponseAndSharedQuotaWhenAiEnabled() {
        login(RoleCode.CUSTOMER); when(service.enabled()).thenReturn(true);
        when(service.answer(any())).thenReturn(new ChatbotService.Answer("Hướng dẫn", "GEMINI"));
        var response=controller.chat(question()); assertEquals("no-store",response.getHeaders().getFirst("Cache-Control"));
        assertEquals("GEMINI",response.getBody().source());
        verify(store).consume(eq("chat:shared-provider"),eq(60),eq(20));
        verify(store).consume(argThat(key->!key.equals("chat:shared-provider")),eq(60),eq(5));
    }
    @Test void throttleAndRedisOutageNeverCallProvider() {
        login(RoleCode.CUSTOMER); when(store.consume(anyString(),eq(60),eq(5))).thenReturn(25L);
        assertEquals("RATE_LIMITED",assertThrows(ThrottleException.class,()->controller.chat(question())).getCode());
        when(store.consume(anyString(),eq(60),eq(5))).thenThrow(new IllegalStateException("offline"));
        assertEquals("THROTTLE_UNAVAILABLE",assertThrows(ThrottleException.class,()->controller.chat(question())).getCode());
        verify(service,never()).answer(any());
    }
}
