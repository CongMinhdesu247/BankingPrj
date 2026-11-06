package com.minh.banking.redis;

import com.minh.banking.security.*;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Pattern;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/** Instantiated only inside the security chain, after authentication and URL authorization. */
public class RateLimitFilter extends OncePerRequestFilter {
    private static final Pattern IPV4_LITERAL = Pattern.compile("[0-9]{1,3}(\\.[0-9]{1,3}){3}");
    private static final Pattern IPV6_LITERAL = Pattern.compile("[0-9a-fA-F:]+");
    private static final int MAX_FORWARDED_HEADER_LENGTH = 256;

    private final RateLimitService limits;
    private final SecurityErrorWriter errors;
    private final boolean trustForwardedHeaders;

    public RateLimitFilter(RateLimitService limits, SecurityErrorWriter errors) {
        this(limits, errors, false);
    }

    public RateLimitFilter(RateLimitService limits, SecurityErrorWriter errors, boolean trustForwardedHeaders) {
        this.limits = limits;
        this.errors = errors;
        this.trustForwardedHeaders = trustForwardedHeaders;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            if (request.getMethod().equals("POST")) {
                var authentication = SecurityContextHolder.getContext().getAuthentication();
                if (request.getServletPath().startsWith("/api/v1/auth/")) {
                    limits.check("login", resolveClientIp(request, trustForwardedHeaders));
                } else if (request.getServletPath().startsWith("/api/v1/me/security/")
                        && !request.getServletPath().equals("/api/v1/me/security/logout")) {
                    String actor = authentication != null && authentication.getPrincipal() instanceof BankingPrincipal principal
                        ? principal.id().toString() : resolveClientIp(request, trustForwardedHeaders);
                    limits.check("login", actor);
                } else if (request.getServletPath().equals("/api/v1/transfers")) {
                    if (authentication != null && authentication.getPrincipal() instanceof BankingPrincipal principal && principal.isCustomer()) {
                        limits.check("transfer", principal.id().toString());
                    }
                }
            }
        } catch (ThrottleException failure) {
            response.setHeader("Retry-After", Long.toString(failure.retryAfter()));
            response.setHeader("Cache-Control", "no-store");
            errors.write(request, response, com.minh.banking.common.exception.ErrorCode.valueOf(failure.getCode()), failure.getMessage());
            return;
        }
        chain.doFilter(request, response);
    }

    /**
     * When running behind a trusted local reverse proxy (Nginx in Docker) and an edge tunnel,
     * scan X-Forwarded-For from right to left and return the rightmost non-private IP address.
     * This prevents clients from spoofing earlier hops in X-Forwarded-For while isolating rate-limit
     * buckets per public visitor.
     */
    static String resolveClientIp(HttpServletRequest request, boolean trustForwardedHeaders) {
        String remoteAddr = request.getRemoteAddr();
        if (!trustForwardedHeaders || !isInternalAddress(remoteAddr)) {
            return remoteAddr;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank() && forwarded.length() <= MAX_FORWARDED_HEADER_LENGTH) {
            String[] hops = forwarded.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String candidate = hops[i].strip();
                InetAddress parsed = parseIpLiteral(candidate);
                if (parsed != null && !isInternalAddress(parsed)) {
                    return parsed.getHostAddress();
                }
            }
        }
        String cfIp = request.getHeader("CF-Connecting-IP");
        if (cfIp != null && !cfIp.isBlank() && cfIp.length() <= 64) {
            InetAddress parsed = parseIpLiteral(cfIp.strip());
            if (parsed != null && !isInternalAddress(parsed)) {
                return parsed.getHostAddress();
            }
        }
        return remoteAddr;
    }

    private static InetAddress parseIpLiteral(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        boolean ipv4 = IPV4_LITERAL.matcher(value).matches();
        boolean ipv6 = !ipv4 && value.indexOf(':') >= 0 && IPV6_LITERAL.matcher(value).matches();
        if (!ipv4 && !ipv6) {
            return null;
        }
        try {
            return InetAddress.getByName(value);
        } catch (UnknownHostException ignored) {
            return null;
        }
    }

    private static boolean isInternalAddress(String ip) {
        InetAddress parsed = parseIpLiteral(ip);
        return parsed != null && isInternalAddress(parsed);
    }

    private static boolean isInternalAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress() || address.isAnyLocalAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            // RFC 6598 Shared Address Space (100.64.0.0/10)
            return (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
        }
        if (bytes.length == 16) {
            // RFC 4193 IPv6 Unique Local Addresses (fc00::/7)
            return (bytes[0] & 0xfe) == 0xfc;
        }
        return false;
    }
}
