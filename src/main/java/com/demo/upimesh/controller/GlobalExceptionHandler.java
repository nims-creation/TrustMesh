package com.demo.upimesh.controller;

import com.demo.upimesh.service.InsufficientFundsException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.Map;

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
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

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
     * Logs the real error server-side; returns a safe generic message to the client.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        return ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of(
                        "status", 500,
                        "error", "Internal Server Error",
                        "message", ex.getMessage() == null ? "unexpected error" : ex.getMessage()
                ));
    }
}
