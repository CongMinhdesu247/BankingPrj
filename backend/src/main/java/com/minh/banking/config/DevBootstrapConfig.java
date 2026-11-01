package com.minh.banking.config;

import com.minh.banking.user.service.BootstrapAdminService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;

@Configuration @Profile("dev")
public class DevBootstrapConfig {
    @Bean
    @ConditionalOnProperty(name = "banking.security.bootstrap.enabled", havingValue = "true")
    ApplicationRunner bootstrapAdmin(BootstrapAdminService bootstrap,
            @Value("${banking.security.bootstrap.username:admin}") String username,
            @Value("${banking.security.bootstrap.password:}") String password) {
        return arguments -> bootstrap.seed(username, password);
    }
}
