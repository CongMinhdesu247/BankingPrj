package com.minh.banking.common.response;

import java.time.Instant;
import java.util.List;

public record ApiError(String code, String message, List<FieldViolation> fieldErrors,
        String requestId, Instant timestamp, String path) {
    public record FieldViolation(String field, String message) {}
}
