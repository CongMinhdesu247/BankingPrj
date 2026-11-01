package com.minh.banking.redis;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("banking.redis")
public record RedisProperties(
        @NotBlank @Pattern(regexp="[a-zA-Z0-9:_-]{1,100}") String namespace,
        @Min(1) int loginMaxFailures, @Min(1) int loginTtlSeconds,
        @Min(1) int loginRateLimit, @Min(1) int transferRateLimit,
        @Min(1) int rateWindowSeconds, @Min(1) int cacheTtlSeconds,
        @Min(1) int counterWindowSeconds, @Min(1) int counterTtlSeconds) {}
