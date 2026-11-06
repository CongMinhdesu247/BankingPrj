package com.minh.banking.common.exception;

import com.minh.banking.common.response.ApiError;
import com.minh.banking.common.response.ApiError.FieldViolation;
import com.minh.banking.common.response.ApiErrorFactory;
import com.minh.banking.config.RequestIdFilter;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.http.*;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.context.request.*;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private final ApiErrorFactory errors;

    public GlobalExceptionHandler(ApiErrorFactory errors) {
        this.errors = errors;
    }

    @ExceptionHandler(BusinessException.class)
    ResponseEntity<Object> business(BusinessException exception, WebRequest request) {
        var response = ResponseEntity.status(exception.getStatus());
        if (exception.getStatus() == HttpStatus.SERVICE_UNAVAILABLE) response.header("Retry-After", "1");
        if (exception instanceof com.minh.banking.redis.ThrottleException throttle)
        response.header("Retry-After", Long.toString(throttle.retryAfter())).header("Cache-Control", "no-store");
        return response.body(
            error(exception.getCode(), exception.getMessage(), List.of(), request));
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Object> conflict(DataIntegrityViolationException exception, WebRequest request) {
        String constraint = "";
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException violation && violation.getConstraintName() != null) {
                constraint = violation.getConstraintName();
                break;
            }
            if (cause instanceof java.sql.SQLException sql && sql.getMessage() != null) {
                for (String known : List.of("users_username_key", "users_customer_id_key",
                    "accounts_account_number_key", "customers_customer_code_key", "customers_email_key")) {
                    if (sql.getMessage().contains(known)) {
                        constraint = known;
                        break;
                    }
                }
                if (!constraint.isEmpty()) break;
            }
        }
        String code;
        String message;
        if ("users_username_key".equals(constraint) || "users_customer_id_key".equals(constraint)) {
            code = "USER_CONFLICT";
            message = "Username or customer link already exists.";
        } else if ("accounts_account_number_key".equals(constraint)) {
            code = "ACCOUNT_NUMBER_CONFLICT";
            message = "Account number generation collided. Please submit again.";
        } else if ("customers_customer_code_key".equals(constraint) || "customers_email_key".equals(constraint)) {
            code = "CUSTOMER_CONFLICT";
            message = "Customer code or email already exists.";
        } else {
            code = "DATA_CONFLICT";
            message = "Data violates a database constraint.";
        }
        return ResponseEntity.status(HttpStatus.CONFLICT).body(error(code, message, List.of(), request));
    }

    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    ResponseEntity<Object> forbidden(Exception exception, WebRequest request) {
        return ResponseEntity.status(403).body(error("FORBIDDEN", "Access is denied.", List.of(), request));
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
        HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> fields = exception.getBindingResult().getFieldErrors().stream()
        .map(field -> new FieldViolation(field.getField(), field.getDefaultMessage())).toList();
        return new ResponseEntity<>(error("VALIDATION_ERROR", "Request validation failed.", fields, request),
            headers, status);
    }

    @ExceptionHandler({PessimisticLockingFailureException.class, OptimisticLockingFailureException.class,
            QueryTimeoutException.class, TransactionTimedOutException.class})
    ResponseEntity<Object> busy(Exception exception, WebRequest request) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1")
        .body(error("ACCOUNT_BUSY", "Account is busy. Please try again later.", List.of(), request));
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException exception,
        HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (exception.isForReturnValue()) return handleExceptionInternal(exception, null, headers, status, request);
        var fields = exception.getParameterValidationResults().stream().flatMap(result ->
            result.getResolvableErrors().stream().map(violation -> new FieldViolation(
            result.getMethodParameter().getParameterName() == null ? "request" : result.getMethodParameter().getParameterName(),
            violation.getDefaultMessage()))).toList();
        return new ResponseEntity<>(error("INVALID_REQUEST", "Request parameter validation failed.", fields, request), headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(org.springframework.beans.TypeMismatchException exception,
        HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String field = exception instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException argument
        ? argument.getName() : "request";
        return new ResponseEntity<>(error("INVALID_REQUEST", "Request parameter has an invalid format.",
            List.of(new FieldViolation(field, "Invalid format")), request), headers, status);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception exception, Object body,
        HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        String code = switch (status.value()) {
            case 404 -> "RESOURCE_NOT_FOUND";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status.is5xxServerError() ? "INTERNAL_ERROR" : "INVALID_REQUEST";
        };
        return new ResponseEntity<>(error(code, "The request cannot be processed.", List.of(), request),
            headers, status);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> unexpected(Exception exception, WebRequest request) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof java.sql.SQLException sql && sql.getSQLState() != null &&
                java.util.Set.of("55P03", "40P01", "40001", "57014").contains(sql.getSQLState())) {
                return busy(exception, request);
            }
        }
        // Avoid logging exception payloads/SQL bind values containing contact data.
        log.error("Unhandled request failure: {}", exception.getClass().getName());
        return ResponseEntity.internalServerError().body(
            error("INTERNAL_ERROR", "An unexpected error occurred.", List.of(), request));
    }

    private ApiError error(String code, String message, List<FieldViolation> fields, WebRequest request) {
        if (request instanceof ServletWebRequest servlet) {
            return errors.create(ErrorCode.valueOf(code), message, fields, servlet.getRequest());
        }
        String path = request instanceof ServletWebRequest servlet ? servlet.getRequest().getRequestURI() : "";
        return new ApiError(code, message, fields,
            (String) request.getAttribute(RequestIdFilter.ATTRIBUTE, RequestAttributes.SCOPE_REQUEST),
            Instant.now(), path);
    }
}
