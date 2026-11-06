package com.minh.banking.config;

import com.minh.banking.common.exception.ErrorCode;
import com.minh.banking.security.*;
import jakarta.servlet.DispatcherType;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.*;

@Configuration @EnableMethodSecurity
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, CurrentUserJwtConverter converter, SecurityErrorWriter errors,
        com.minh.banking.redis.RateLimitService limits) throws Exception {
        var entry = (org.springframework.security.web.AuthenticationEntryPoint)
        (request, response, exception) -> errors.write(request, response, ErrorCode.UNAUTHENTICATED);
        var denied = (org.springframework.security.web.access.AccessDeniedHandler)
        (request, response, exception) -> errors.write(request, response, ErrorCode.FORBIDDEN);
        return http.addFilterAfter(new com.minh.banking.redis.RateLimitFilter(limits, errors),
            org.springframework.security.web.access.intercept.AuthorizationFilter.class)
        .csrf(AbstractHttpConfigurer::disable).cors(Customizer.withDefaults())
        .formLogin(AbstractHttpConfigurer::disable).httpBasic(AbstractHttpConfigurer::disable)
        .logout(AbstractHttpConfigurer::disable).requestCache(AbstractHttpConfigurer::disable)
        .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(authorize -> authorize
            .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
            .requestMatchers("/api/v1/auth/login", "/api/v1/auth/register", "/api/v1/auth/forgot-password", "/api/v1/auth/reset-password", "/api/v1/auth/recovery-options", "/v3/api-docs/**", "/v3/api-docs.yaml", "/swagger-ui/**", "/swagger-ui.html").permitAll()
            .requestMatchers("/api/v1/fraud-rules", "/api/v1/fraud-rules/**").hasRole("ADMIN")
            .requestMatchers("/api/v1/fraud/**", "/api/v1/fraud-alerts", "/api/v1/fraud-alerts/**").hasRole("RISK_OFFICER")
            .requestMatchers("/api/v1/staff/**").hasAnyRole("BANK_STAFF", "RISK_OFFICER", "ADMIN")
            .requestMatchers("/api/v1/**").authenticated().anyRequest().denyAll())
        .exceptionHandling(exceptions -> exceptions.authenticationEntryPoint(entry).accessDeniedHandler(denied))
        .oauth2ResourceServer(resource -> resource.jwt(jwt -> jwt.jwtAuthenticationConverter(converter))
            .authenticationEntryPoint(entry).accessDeniedHandler(denied)).build();
    }

    @Bean
    org.springframework.web.filter.CorsFilter corsFilter(
        @org.springframework.beans.factory.annotation.Qualifier("corsConfigurationSource") CorsConfigurationSource source,
        SecurityErrorWriter errors) {
        var filter = new org.springframework.web.filter.CorsFilter(source);
        filter.setCorsProcessor((configuration, request, response) -> new DefaultCorsProcessor() {
                @Override
                protected void rejectRequest(org.springframework.http.server.ServerHttpResponse ignored) throws java.io.IOException {
                    errors.write(request, response, ErrorCode.FORBIDDEN);
                }
            }
            .processRequest(configuration, request, response));
        return filter;
    }

    @Bean
    org.springframework.boot.web.servlet.FilterRegistrationBean<org.springframework.web.filter.CorsFilter> corsRegistration(
        org.springframework.web.filter.CorsFilter filter) {
        var registration = new org.springframework.boot.web.servlet.FilterRegistrationBean<>(filter);
        registration.setEnabled(false); // Security chain owns CORS; do not run the filter twice.
        return registration;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(
        @Value("${banking.security.allowed-origins:http://localhost:5173,http://127.0.0.1:5173}") String allowedOrigins) {
        var configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(Arrays.stream(allowedOrigins.split(",")).map(String::strip).toList());
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-Request-Id", "Idempotency-Key"));
        configuration.setExposedHeaders(List.of("X-Request-Id", "Location", "Retry-After", "Idempotency-Replayed"));
        configuration.setAllowCredentials(false);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
