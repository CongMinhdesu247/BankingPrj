package com.minh.banking.kafka;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated @ConfigurationProperties("banking.kafka")
public record KafkaProperties(boolean enabled,@NotBlank @Pattern(regexp="[a-zA-Z0-9][a-zA-Z0-9._-]{0,100}") String topicPrefix,
        @Min(1) @Max(16) int partitions,@Min(1) int retryBackoffMs,@Min(0) @Max(10) int retryAttempts) {}
