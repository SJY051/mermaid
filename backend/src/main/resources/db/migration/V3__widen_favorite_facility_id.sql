-- Base64url HIRA identifiers can exceed the prior 120-character limit once provider-prefixed.
-- This new migration deliberately leaves V2 unchanged: Flyway validates applied checksums at startup.
ALTER TABLE favorite_facility
    MODIFY COLUMN facility_id VARCHAR(255) NOT NULL
        COMMENT 'Provider-namespaced id, e.g. facility:nmc:C1110693. Never a name.';
