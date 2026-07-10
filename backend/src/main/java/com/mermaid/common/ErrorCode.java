package com.mermaid.common;

import org.springframework.http.HttpStatus;

/**
 * The complete set of errors this API can return (spec §5-2).
 *
 * <p>The frontend switches on {@code code}, never on the message. {@code retryable} tells it whether
 * offering a "try again" button is honest.
 *
 * <p>Adding a code is an API contract change: update the spec and the frontend's union type in the
 * same PR.
 */
public enum ErrorCode {
    INVALID_REQUEST(HttpStatus.BAD_REQUEST, false),
    INPUT_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, false),
    UNSUPPORTED_MODEL(HttpStatus.BAD_REQUEST, false),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, true),
    LOCATION_REQUIRED(HttpStatus.BAD_REQUEST, false),
    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, false),

    AI_PROVIDER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, true),
    AI_PROVIDER_ERROR(HttpStatus.BAD_GATEWAY, true),
    /** The model's answer failed schema validation or a post-processing invariant. */
    AI_SCHEMA_INVALID(HttpStatus.BAD_GATEWAY, true),

    FACILITY_PROVIDER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, true),
    DRUG_PROVIDER_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, true),
    /** A government API is down, or answered 200 with an error envelope. */
    SOURCE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, true),
    SOURCE_PAYLOAD_INVALID(HttpStatus.BAD_GATEWAY, true),

    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, false);

    private final HttpStatus status;
    private final boolean retryable;

    ErrorCode(HttpStatus status, boolean retryable) {
        this.status = status;
        this.retryable = retryable;
    }

    public HttpStatus status() {
        return status;
    }

    public boolean retryable() {
        return retryable;
    }
}
