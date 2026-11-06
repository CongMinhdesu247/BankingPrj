package com.minh.banking.auth.dto;

import com.minh.banking.user.dto.UserResponse;

public record TokenResponse(String accessToken, String tokenType, long expiresIn, UserResponse user) {
    @Override public String toString() {
        return "TokenResponse[accessToken=REDACTED]";
    }
}
