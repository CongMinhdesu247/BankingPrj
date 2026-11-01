package com.minh.banking.common.response;

import com.minh.banking.common.exception.ErrorCode;
import com.minh.banking.config.RequestIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class ApiErrorFactory {
    public ApiError create(ErrorCode code, String message, List<ApiError.FieldViolation> fields,
            HttpServletRequest request) {
        return new ApiError(code.name(), message, fields,
            (String) request.getAttribute(RequestIdFilter.ATTRIBUTE), Instant.now(), request.getRequestURI());
    }
}
