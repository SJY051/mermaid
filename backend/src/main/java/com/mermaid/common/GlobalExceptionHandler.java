package com.mermaid.common;

import jakarta.validation.ConstraintViolationException;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * One place where every failure becomes the error envelope of spec §5-2.
 *
 * <p>Two rules, both about what the browser is told:
 *
 * <ol>
 *   <li><b>Never leak internals.</b> A Jackson-unparseable request body uses only a stable log event
 *       and the MDC request id. The client gets a code and a sentence written for a person.
 *   <li><b>Say whether retrying helps.</b> A government API that timed out is worth another try. A
 *       malformed query is not. {@code retryable} decides whether the UI offers a button.
 * </ol>
 *
 * <p>The requirement behind this: when a public API is slow or down, the frontend must not go blank
 * (original 요구사항 명세서 §7).
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handled(ApiException e) {
        log.warn("{} — {}", e.code(), e.getMessage());
        return body(e.code(), userMessageFor(e.code()), e.details());
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NotFoundException e) {
        log.warn("not found: {}", e.getMessage());
        return body(ErrorCode.RESOURCE_NOT_FOUND, "We could not find that.", Map.of());
    }

    /**
     * A path no controller claims.
     *
     * <p>DispatcherServlet finds no handler mapping, falls through to the static-resource handler,
     * and that throws. Without this method the request lands in {@link #unexpected} and a typo in a
     * URL is reported to the client as our server having crashed — a 500 the frontend would offer to
     * escalate, when the honest answer is that the path does not exist.
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> noResource(NoResourceFoundException e) {
        log.warn("no handler for {}", e.getResourcePath());
        return body(ErrorCode.RESOURCE_NOT_FOUND, userMessageFor(ErrorCode.RESOURCE_NOT_FOUND), Map.of());
    }

    @ExceptionHandler(PublicApiException.class)
    public ResponseEntity<Map<String, Object>> publicApiDown(PublicApiException e) {
        log.error("public API call failed", e);
        return body(ErrorCode.SOURCE_UNAVAILABLE, userMessageFor(ErrorCode.SOURCE_UNAVAILABLE), Map.of());
    }

    @ExceptionHandler(FixtureIntegrityException.class)
    public ResponseEntity<Map<String, Object>> fixtureIntegrity(FixtureIntegrityException e) {
        // The reason is a bounded enum. Do not log the exception or fixture name: parser messages
        // can contain captured payload values.
        log.error("fixture_integrity_failure reason={}", e.reason());
        return body(ErrorCode.INTERNAL_ERROR, userMessageFor(ErrorCode.INTERNAL_ERROR), Map.of());
    }

    /**
     * Every shape a bad request can take.
     *
     * <p>{@code @Min}/{@code @Max} on a {@code @RequestParam} throws {@link
     * ConstraintViolationException}, not {@code MethodArgumentNotValidException} — that one is only
     * for {@code @RequestBody}. Missing it meant {@code ?lat=999} returned a 500 saying "something
     * went wrong on our side", when the problem was entirely on the caller's.
     */
    // IllegalArgumentException is deliberately NOT here. Spring, Jackson and Spring Cache all throw
    // it for internal problems — a cache that refuses a null value is our bug, not the caller's — and
    // mapping it to INVALID_REQUEST told a user their request was malformed when it was perfect.
    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        ConstraintViolationException.class,
        HandlerMethodValidationException.class,
        MissingServletRequestParameterException.class,
        MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Map<String, Object>> invalid(Exception e) {
        // Validation exceptions retain rejected values in their messages and arguments. Keep only a
        // bounded reason; the request id in MDC is the correlation key.
        log.warn("invalid_request reason=" + validationReason(e));
        return body(ErrorCode.INVALID_REQUEST, firstFieldError(e), Map.of());
    }

    /**
     * A request body that Jackson could not parse.
     *
     * <p>The exception and its causes can retain user-authored body fragments. Chat bodies contain
     * symptoms, so this path records only a stable event name; the request id already in MDC is the
     * correlation key.
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> malformedBody() {
        log.warn("invalid request body");
        return body(
                ErrorCode.INVALID_REQUEST,
                userMessageFor(ErrorCode.INVALID_REQUEST),
                Map.of());
    }

    @ExceptionHandler(UnsupportedOperationException.class)
    public ResponseEntity<Map<String, Object>> notImplemented(UnsupportedOperationException e) {
        // A TODO(team) stub was called. Say so plainly rather than pretending it worked — and say it
        // as 501, not 500. Both are our fault; only one of them means something is broken.
        log.warn("not implemented yet: {}", e.getMessage());
        return body(ErrorCode.NOT_IMPLEMENTED, userMessageFor(ErrorCode.NOT_IMPLEMENTED), Map.of());
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> unexpected(Exception e) {
        log.error("unhandled exception", e);
        return body(ErrorCode.INTERNAL_ERROR, userMessageFor(ErrorCode.INTERNAL_ERROR), Map.of());
    }

    // ---------------------------------------------------------------------

    private static String userMessageFor(ErrorCode code) {
        return switch (code) {
            case SOURCE_UNAVAILABLE, FACILITY_PROVIDER_TIMEOUT, DRUG_PROVIDER_TIMEOUT ->
                    "A government data service is not responding. Please try again shortly.";
            case AI_PROVIDER_TIMEOUT, AI_PROVIDER_ERROR ->
                    "The assistant is not responding. Please try again.";
            case AI_SCHEMA_INVALID ->
                    "I could not verify that answer against official data, so I will not show it.";
            case RATE_LIMITED -> "Too many requests. Please wait a moment.";
            case LOCATION_REQUIRED -> "This search needs your location.";
            case RESOURCE_NOT_FOUND -> "We could not find that.";
            case INPUT_TOO_LARGE -> "That message is too long.";
            case UNSUPPORTED_MODEL -> "That model is not available.";
            case INVALID_REQUEST -> "That request was not valid.";
            case SOURCE_PAYLOAD_INVALID -> "A government data service returned something unexpected.";
            case NOT_IMPLEMENTED -> "That feature is not built yet.";
            case INTERNAL_ERROR -> "Something went wrong on our side.";
        };
    }

    /**
     * Names the offending parameter, in English.
     *
     * <p>Bean Validation's own messages follow the server's locale — this server answers a Korean
     * JVM with "90 이하여야 합니다". Our users read English, so we take the field name and write the
     * sentence ourselves. The exact bound is in the API docs; repeating it here would only drift.
     */
    private static String firstFieldError(Exception e) {
        String field = null;

        if (e instanceof MethodArgumentNotValidException manv) {
            field =
                    manv.getBindingResult().getFieldErrors().stream()
                            .findFirst()
                            .map(FieldError::getField)
                            .orElse(null);
        } else if (e instanceof ConstraintViolationException cve) {
            field =
                    cve.getConstraintViolations().stream()
                            .findFirst()
                            .map(v -> lastNode(v.getPropertyPath().toString()))
                            .orElse(null);
        } else if (e instanceof MissingServletRequestParameterException mspe) {
            return "Missing required parameter: " + mspe.getParameterName();
        } else if (e instanceof MethodArgumentTypeMismatchException mtme) {
            return "Parameter '" + mtme.getName() + "' has the wrong type.";
        }

        return field == null
                ? "That request was not valid."
                : "Parameter '" + field + "' is out of range or malformed.";
    }

    private static String validationReason(Exception e) {
        if (e instanceof MethodArgumentNotValidException) {
            return "body_validation";
        }
        if (e instanceof ConstraintViolationException) {
            return "constraint_violation";
        }
        if (e instanceof HandlerMethodValidationException) {
            return "method_validation";
        }
        if (e instanceof MissingServletRequestParameterException) {
            return "missing_parameter";
        }
        if (e instanceof MethodArgumentTypeMismatchException) {
            return "type_mismatch";
        }
        return "invalid_request";
    }

    /** {@code nearby.lat} → {@code lat} */
    private static String lastNode(String propertyPath) {
        int dot = propertyPath.lastIndexOf('.');
        return dot < 0 ? propertyPath : propertyPath.substring(dot + 1);
    }

    /** {@code details} must never carry a stack trace or anything the user typed. */
    private static ResponseEntity<Map<String, Object>> body(
            ErrorCode code, String message, Map<String, Object> details) {

        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code.name());
        error.put("message", message);
        error.put("retryable", code.retryable());
        error.put("request_id", RequestIdFilter.current());
        if (!details.isEmpty()) {
            error.put("details", details);
        }

        return ResponseEntity.status(code.status()).body(Map.of("error", error));
    }
}
