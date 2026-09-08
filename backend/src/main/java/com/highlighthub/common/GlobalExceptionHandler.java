package com.highlighthub.common;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    public record ApiError(String code, String message, String requestId, Map<String, Object> details) {}

    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ApiError> handleBusiness(BusinessException ex, HttpServletRequest req) {
        return build(ex.getHttpStatus(), ex.getCode(), ex.getMessage(), null, req);
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuth(org.springframework.security.core.AuthenticationException ex,
                                               HttpServletRequest req) {
        // never reveal whether the username exists
        return build(401, ErrorCodes.UNAUTHORIZED, "invalid username or password", null, req);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, Object> details = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> details.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        return build(400, ErrorCodes.VALIDATION_FAILED, "request validation failed", details, req);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> handleTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        return build(413, ErrorCodes.PAYLOAD_TOO_LARGE, "request body too large", null, req);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNoResource(NoResourceFoundException ex, HttpServletRequest req) {
        return build(404, ErrorCodes.NOT_FOUND, "resource not found", null, req);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnknown(Exception ex, HttpServletRequest req) {
        log.error("unhandled error on {} {}: {}", req.getMethod(), req.getRequestURI(), ex.toString(), ex);
        return build(500, ErrorCodes.INTERNAL, "internal server error", null, req);
    }

    private ResponseEntity<ApiError> build(int status, String code, String message,
                                           Map<String, Object> details, HttpServletRequest req) {
        String requestId = MDC.get("requestId");
        if (requestId == null) requestId = req.getHeader("X-Request-Id");
        if (requestId == null) requestId = java.util.UUID.randomUUID().toString();
        if (status >= 500) {
            log.warn("error response status={} code={} message={}", status, code, message);
        }
        return ResponseEntity.status(HttpStatus.valueOf(status))
                .header("X-Request-Id", requestId)
                .body(new ApiError(code, message, requestId, details));
    }
}
