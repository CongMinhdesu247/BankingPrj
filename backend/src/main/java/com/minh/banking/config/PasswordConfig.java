package com.minh.banking.config;

import java.util.Map;
import org.springframework.context.annotation.*;
import org.springframework.security.crypto.password.*;

@Configuration
public class PasswordConfig {
    @Bean
    PasswordEncoder passwordEncoder() {
        String id = "pbkdf2@SpringSecurity_v5_8";
        return new DelegatingPasswordEncoder(id, Map.of(id, Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8()));
    }
}
