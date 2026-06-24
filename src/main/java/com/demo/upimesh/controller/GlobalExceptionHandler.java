package com.demo.upimesh.controller;

import com.demo.upimesh.service.InsufficientFundsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Centralised error handling for all REST controllers.
 *
 * Why @ControllerAdvice vs. try/catch in each controller?
 *   - Single responsibility: controllers handle happy-path logic,
 *     error mapping lives here.
 *   - Consistent HTTP status codes and response shape across the entire API.
 *   - Adding a new exception type only requires one new @ExceptionHandler method.
 *
 * Error shapes used:
 *   - 400 Bad Request  → @Valid constraint violations (MethodArgumentNotValidException)
 *   - 422 Unprocessable → InsufficientFundsException (valid request, business rule failed)
 *   - 500 Internal     → anything unexpected (guards against raw stack traces leaking)
 *
 * SECURITY: The catch-all handler logs the real exception server-side with a
 * correlation ID, but returns ONLY the correlation ID to the client.
 * This prevents internal implementation details (class names, DB schema,
 * stack traces, file paths) from leaking to potential attackers via error responses.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * Maps HTTP method mismatch (e.g. GET on a POST endpoint) to 405 Method Not Allowed.
     * Without this, the exception falls through to the catch-all and returns 500.
     */
    @ExceptionHandler(org.springframework.web.HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<Map<String, Object>> handleMethodNotSupported(
            org.springframework.web.HttpRequestMethodNotSupportedException ex) {
        return ResponseEntity
                .status(HttpStatus.METHOD_NOT_ALLOWED)
                .body(Map.of(
                        "status", 405,
                        "error", "Method Not Allowed",
                        "message", ex.getMessage()
                ));
    }

    /**
     * Maps missing static resources (like favicon.ico or mistyped paths) to 404 Not Found.
     */
    @ExceptionHandler(org.springframework.web.servlet.resource.NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> handleNoResourceFound(
            org.springframework.web.servlet.resource.NoResourceFoundException ex) {
        return ResponseEntity
                .status(HttpStatus.NOT_FOUND)
                .body(Map.of(
                        "status", 404,
                        "error", "Not Found",
                        "message", ex.getMessage()
                ));
    }

    /**
     * Maps @Valid / @Validated failures to HTTP 400 with a field-level error list.
     * Without this, Spring returns a generic 400 with no field details.
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        List<String> errors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();

        return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(Map.of(
                        "status", 400,
                        "error", "Validation Failed",
                        "violations", errors
                ));
    }

    /**
     * Maps InsufficientFundsException to HTTP 422 Unprocessable Entity.
     * 422 is more accurate than 400 (request was valid, business rule rejected it).
     * The sender VPA, available balance, and requested amount are safe to return
     * as they are business-rule facts the caller already knows.
     */
    @ExceptionHandler(InsufficientFundsException.class)
    public ResponseEntity<Map<String, Object>> handleInsufficientFunds(InsufficientFundsException ex) {
        return ResponseEntity
                .status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of(
                        "status", 422,
                        "error", "Insufficient Funds",
                        "senderVpa", ex.getSenderVpa(),
                        "available", ex.getAvailable(),
                        "requested", ex.getRequested()
                ));
    }

    /**
     * Catch-all: prevents raw stack traces from leaking in production.
     *
     * SECURITY: ex.getMessage() is intentionally NOT included in the response body.
     * Internal error messages can contain:
     *   - Database table/column names (schema leak)
     *   - File paths (directory structure leak)
     *   - Class and method names (implementation leak)
     *   - SQL fragments (injection surface mapping)
     *
     * Instead, a random correlation ID is generated and logged server-side so that
     * support/ops can trace the error in logs without exposing details to clients.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        String correlationId = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        // Log the FULL exception server-side (including message + stack trace)
        log.error("[{}] Unhandled exception: {}", correlationId, ex.getMessage(), ex);
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "status", 500,
                        "error", "Internal Server Error",
                        "message", "An unexpected error occurred. Reference ID: " + correlationId,
                        "referenceId", correlationId
                ));
    }
}
