package com.mermaid.common;

/**
 * A packaged local fixture is missing or cannot be parsed. This is our artifact defect, not an
 * upstream outage.
 */
public final class FixtureIntegrityException extends RuntimeException {

    public enum Reason {
        MISSING,
        CORRUPT
    }

    private final Reason reason;

    private FixtureIntegrityException(Reason reason, Throwable cause) {
        super("local fixture integrity failure", cause);
        this.reason = reason;
    }

    static FixtureIntegrityException missing() {
        return new FixtureIntegrityException(Reason.MISSING, null);
    }

    static FixtureIntegrityException corrupt(Throwable cause) {
        return new FixtureIntegrityException(Reason.CORRUPT, cause);
    }

    public Reason reason() {
        return reason;
    }
}
