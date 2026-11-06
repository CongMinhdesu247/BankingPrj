package com.minh.banking.redis;

import com.minh.banking.security.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Instantiated only inside the security chain, after authentication and URL authorization. */
public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimitService limits;
    private final SecurityErrorWriter errors;
    public RateLimitFilter(RateLimitService limits,SecurityErrorWriter errors) { this.limits=limits; this.errors=errors; }
    @Override
    protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)
            throws ServletException,IOException {
        try {
            if (request.getMethod().equals("POST")) {
                var authentication=SecurityContextHolder.getContext().getAuthentication();
                if (request.getServletPath().startsWith("/api/v1/auth/")) {
                    // server.forward-headers-strategy=none: never trust caller-supplied X-Forwarded-For.
                    limits.check("login",request.getRemoteAddr());
                } else if (request.getServletPath().startsWith("/api/v1/me/security/")
                        && !request.getServletPath().equals("/api/v1/me/security/logout")) {
                    String actor=authentication!=null && authentication.getPrincipal() instanceof BankingPrincipal principal
                        ? principal.id().toString() : request.getRemoteAddr();
                    limits.check("login",actor);
                } else if (request.getServletPath().equals("/api/v1/transfers")) {
                    if (authentication!=null && authentication.getPrincipal() instanceof BankingPrincipal principal && principal.isCustomer())
                        limits.check("transfer",principal.id().toString());
                }
            }
        } catch (ThrottleException failure) {
            response.setHeader("Retry-After",Long.toString(failure.retryAfter()));
            response.setHeader("Cache-Control","no-store");
            errors.write(request,response,com.minh.banking.common.exception.ErrorCode.valueOf(failure.getCode()),failure.getMessage());
            return;
        }
        chain.doFilter(request,response);
    }
}
