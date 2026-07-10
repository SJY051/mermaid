package com.mermaid.common;

/** A data.go.kr call failed, timed out, or answered with an error envelope. */
public class PublicApiException extends RuntimeException {

    public PublicApiException(String message, Throwable cause) {
        super(message, cause);
    }

    public PublicApiException(String message) {
        super(message);
    }
}
