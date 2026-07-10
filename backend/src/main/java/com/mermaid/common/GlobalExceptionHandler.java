package com.mermaid.common;

import java.time.Instant;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * One place where every error becomes a predictable JSON body.
 *
 * <p>The requirement that drives this: when a public API is down or slow, the frontend must not go
 * blank (spec §7 of the original 요구사항 명세서). A 503 with a readable message lets the UI show a
 * default notice instead.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        return body(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(PublicApiException.class)
    public ResponseEntity<Map<String, Object>> publicApiDown(PublicApiException e) {
        log.error("Public API call failed", e);
        return body(
                HttpStatus.SERVICE_UNAVAILABLE,
                "A government data service is not responding. Please try again shortly.");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalid(MethodArgumentNotValidException e) {
        String detail =
                e.getBindingResult().getFieldErrors().stream()
                        .findFirst()
                        .map(f -> f.getField() + ": " + f.getDefaultMessage())
                        .orElse("Invalid request");
        return body(HttpStatus.BAD_REQUEST, detail);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("Unhandled exception", e);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Something went wrong on our side.");
    }

    private ResponseEntity<Map<String, Object>> body(HttpStatus status, String message) {
        return ResponseEntity.status(status)
                .body(
                        Map.of(
                                "timestamp", Instant.now().toString(),
                                "status", status.value(),
                                "message", message == null ? status.getReasonPhrase() : message));
    }
}
