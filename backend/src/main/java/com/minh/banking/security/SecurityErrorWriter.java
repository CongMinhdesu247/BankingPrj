package com.minh.banking.security;

import com.minh.banking.common.exception.ErrorCode;
import com.minh.banking.common.response.ApiErrorFactory;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class SecurityErrorWriter {
    private final ApiErrorFactory errors;
    private final JsonMapper json;
    public SecurityErrorWriter(ApiErrorFactory errors, JsonMapper json) { this.errors = errors; this.json = json; }
    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode code) throws IOException {
        write(request,response,code,code == ErrorCode.UNAUTHENTICATED ? "Authentication is required." : "Access is denied.");
    }
    public void write(HttpServletRequest request,HttpServletResponse response,ErrorCode code,String message) throws IOException {
        response.setStatus(code.status().value());
        response.setContentType("application/json");
        if (code == ErrorCode.UNAUTHENTICATED) response.setHeader("WWW-Authenticate", "Bearer");
        json.writeValue(response.getOutputStream(), errors.create(code,message,List.of(),request));
    }
}
