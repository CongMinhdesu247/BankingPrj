package com.minh.banking.config;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.util.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.*;

@Configuration
public class JwtConfig {
    @Bean
    SecretKey jwtSecretKey(@Value("${banking.security.jwt.secret:}") String encoded) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(encoded);
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalStateException("Configure a Base64 JWT_SECRET of at least 32 random bytes");
        }
        if (bytes.length < 32) throw new IllegalStateException("Configure a Base64 JWT_SECRET of at least 32 random bytes");
        return new SecretKeySpec(bytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSecretKey) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(jwtSecretKey));
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey jwtSecretKey,
        @Value("${banking.security.jwt.issuer}") String issuer,
        @Value("${banking.security.jwt.audience}") String audience) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(jwtSecretKey).macAlgorithm(MacAlgorithm.HS256).build();
        var standardClaims = MappedJwtClaimSetConverter.withDefaults(Map.of());
        decoder.setClaimSetConverter(raw -> {
                // The default converter synthesizes iat when absent. Require the actual
                // signed claims before conversion, so synthesized values cannot satisfy policy.
                for (String required : List.of("iss", "sub", "aud", "iat", "nbf", "exp")) {
                    if (raw.get(required) == null) throw new BadJwtException("Required token claim is missing");
                }
                return standardClaims.convert(raw);
            });
        OAuth2TokenValidator<Jwt> requiredClaims = jwt -> {
            boolean valid = jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
            && jwt.getNotBefore() != null && jwt.getAudience() != null && jwt.getAudience().contains(audience);
            try {
                valid &= UUID.fromString(jwt.getSubject()).toString().equals(jwt.getSubject());
            }
            catch (RuntimeException exception) {
                valid = false;
            }
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "Required token claims are invalid", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), requiredClaims));
        return decoder;
    }
}
