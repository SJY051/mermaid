package com.mermaid.common;

import java.util.Map;

/**
 * Any failure the client is allowed to see, carrying the {@link ErrorCode} it maps to.
 *
 * <p>The {@code message} on this exception is <b>internal</b>. {@code GlobalExceptionHandler} sends
 * a code and a safe, user-facing sentence instead — a stack trace or an upstream error string must
 * never reach the browser.
 */
public class ApiException extends RuntimeException {

    private final ErrorCode code;
    private final transient Map<String, Object> details;

    public ApiException(ErrorCode code, String internalMessage) {
        this(code, internalMessage, Map.of(), null);
    }

    public ApiException(ErrorCode code, String internalMessage, Throwable cause) {
        this(code, internalMessage, Map.of(), cause);
    }

    public ApiException(
            ErrorCode code, String internalMessage, Map<String, Object> details, Throwable cause) {
        super(internalMessage, cause);
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public ErrorCode code() {
        return code;
    }

    /** Non-sensitive context, e.g. {@code {"source": "facility"}}. Never a stack trace. */
    public Map<String, Object> details() {
        return details;
    }
}
