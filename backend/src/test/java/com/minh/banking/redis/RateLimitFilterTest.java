package com.minh.banking.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class RateLimitFilterTest {

    @Test
    void untrustedModeAlwaysUsesRemoteAddressAndIgnoresForwardedHeaders() {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("172.18.0.2");
        request.addHeader("X-Forwarded-For", "203.0.113.10, 172.18.0.1");
        request.addHeader("CF-Connecting-IP", "203.0.113.10");

        assertThat(RateLimitFilter.resolveClientIp(request, false)).isEqualTo("172.18.0.2");
    }

    @Test
    void trustedProxyModeExtractsRightmostPublicIpAndIgnoresClientSpoofedLeftHops() {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("172.18.0.2");
        request.addHeader("X-Forwarded-For", "8.8.8.8, 203.0.113.42, 172.18.0.1");

        assertThat(RateLimitFilter.resolveClientIp(request, true)).isEqualTo("203.0.113.42");
    }

    @Test
    void trustedProxyModeIgnoresForwardedHeadersWhenPeerIsAlreadyPublic() {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("198.51.100.25");
        request.addHeader("X-Forwarded-For", "203.0.113.42");

        assertThat(RateLimitFilter.resolveClientIp(request, true)).isEqualTo("198.51.100.25");
    }

    @Test
    void trustedProxyModeUsesCloudflareHeaderWhenForwardedForIsInternalOnly() {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("172.18.0.2");
        request.addHeader("X-Forwarded-For", "172.18.0.1");
        request.addHeader("CF-Connecting-IP", "198.51.100.88");

        assertThat(RateLimitFilter.resolveClientIp(request, true)).isEqualTo("198.51.100.88");
    }

    @Test
    void trustedProxyModeRejectsHostnamesAndOversizedHeadersWithoutDnsLookup() {
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login");
        request.setRemoteAddr("127.0.0.1");
        request.addHeader("X-Forwarded-For", "evil.example.com, invalid_ip, 10.0.0.1");

        assertThat(RateLimitFilter.resolveClientIp(request, true)).isEqualTo("127.0.0.1");
    }
}

