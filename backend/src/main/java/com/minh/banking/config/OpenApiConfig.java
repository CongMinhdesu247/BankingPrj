package com.minh.banking.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.media.*;
import io.swagger.v3.oas.models.responses.ApiResponse;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.*;

@Configuration
public class OpenApiConfig {
    @Bean
    OpenAPI bankingOpenApi() {
        return new OpenAPI().info(new Info().title("Bank Transaction & Fraud Monitoring API").version("v1")
            .description("Portfolio simulation using fictitious data. VND money uses decimal JSON strings. "
                + "History intervals are [from,to), ISO timestamps with timezone. Transfers require Idempotency-Key; reuse it for retries within 7 days. Limits use Asia/Ho_Chi_Minh days. "
                + "Login and transfer throttling use Redis: 429 includes Retry-After; protection outages return 503 THROTTLE_UNAVAILABLE."));
    }

    private Components errorSchemas() {
        var field = new ObjectSchema().addProperty("field", new StringSchema().example("amount"))
            .addProperty("message", new StringSchema().example("Invalid format"));
        var error = new ObjectSchema()
            .addProperty("code", new StringSchema().example("VALIDATION_ERROR"))
            .addProperty("message", new StringSchema().example("Request validation failed."))
            .addProperty("fieldErrors", new ArraySchema().items(new Schema<>().$ref("#/components/schemas/FieldViolation")))
            .addProperty("requestId", new StringSchema().example("demo-request-001"))
            .addProperty("timestamp", new DateTimeSchema())
            .addProperty("path", new StringSchema().example("/api/v1/transfers"));
        error.setRequired(java.util.List.of("code", "message", "fieldErrors", "requestId", "timestamp", "path"));
        return new Components().addSchemas("ApiError", error).addSchemas("FieldViolation", field);
    }

    @Bean
    OpenApiCustomizer errorContracts() {
        return api -> {
            api.getComponents().getSchemas().putAll(errorSchemas().getSchemas());
            api.getComponents().addSecuritySchemes("bearerAuth", new io.swagger.v3.oas.models.security.SecurityScheme()
                .type(io.swagger.v3.oas.models.security.SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT"));
            api.setSecurity(java.util.List.of(new io.swagger.v3.oas.models.security.SecurityRequirement().addList("bearerAuth")));
            api.getPaths().forEach((path, item) -> item.readOperationsMap().forEach((method, operation) -> {
            if (operation.getSummary() == null) operation.setSummary(method.name() + " " + path);
            var codes = new java.util.ArrayList<>(java.util.List.of("400", "404", "500"));
            if (path.equals("/api/v1/auth/login")) {
                operation.setSecurity(java.util.List.of());
                codes.add("401");
            } else {
                codes.add("401"); codes.add("403");
            }
            if (method == PathItem.HttpMethod.POST || method == PathItem.HttpMethod.PATCH || method == PathItem.HttpMethod.PUT) codes.add("409");
            if (path.contains("transfers") || path.endsWith("/status") || path.endsWith("/transfer-limit")) codes.add("503");
            if (method == PathItem.HttpMethod.POST && (path.equals("/api/v1/auth/login") || path.equals("/api/v1/transfers"))) {
                codes.add("429"); if (!codes.contains("503")) codes.add("503");
            }
            for (String code : codes) {
                var response = new ApiResponse().description("ApiError: see docs/api/error-codes.md")
                    .content(new Content().addMediaType("application/json", new MediaType()
                        .schema(new Schema<>().$ref("#/components/schemas/ApiError"))));
                if (code.equals("429") || code.equals("503")) response.addHeaderObject("Retry-After",
                    new io.swagger.v3.oas.models.headers.Header().description("Seconds to wait before retrying; transfer retries must retain the original key/payload")
                        .schema(new IntegerSchema().minimum(java.math.BigDecimal.ONE)));
                operation.getResponses().addApiResponse(code,response);
            }
            }));
        };
    }
}
