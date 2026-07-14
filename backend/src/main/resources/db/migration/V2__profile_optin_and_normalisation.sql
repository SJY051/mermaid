-- Brings the schema in line with spec §2-5 (allergy memory is opt-in) and
-- §2-12 (an allergy verdict must record how confident the match was).
--
-- Why each column exists:
--
--   user_profile.remember_allergies
--     Allergies are a session input by default. They only reach this database
--     when the user explicitly asks us to remember them, and the flag is what
--     makes that promise checkable rather than merely stated.
--
--   allergy_ingredient.normalized_key / confidence
--     "Ibuprofen", "ibuprofen", "IBUPROFEN (200mg)" are one ingredient. The
--     normalised key is what we compare; `confidence` records whether that
--     normalisation was exact, a reviewed synonym, or a guess. A guess must
--     never produce a `blocked` verdict — it produces `warning`.
--
--   favorite_facility.alias
--     "Near my hotel" is how a traveller finds a pharmacy again. The Korean
--     name is not.

ALTER TABLE user_profile
    ADD COLUMN remember_allergies BOOLEAN NOT NULL DEFAULT FALSE
        COMMENT 'Opt-in. FALSE means allergies live only in the browser session.'
        AFTER country_code;

ALTER TABLE allergy_ingredient
    ADD COLUMN normalized_key VARCHAR(255) NULL
        COMMENT 'Lower-cased, dose-stripped form used for matching against MAIN_INGR_ENG.'
        AFTER ingredient_name_ko,
    ADD COLUMN confidence VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN'
        COMMENT 'EXACT | SYNONYM | PARTIAL | UNKNOWN. Only EXACT and SYNONYM may block a drug.'
        AFTER normalized_key;

-- Matching happens on the normalised key, not the raw text the user typed.
CREATE INDEX ix_allergy_normalized_key ON allergy_ingredient (normalized_key);

ALTER TABLE favorite_facility
    ADD COLUMN alias VARCHAR(100) NULL
        COMMENT 'User-chosen label, e.g. "Near my hotel".'
        AFTER facility_type;

-- facility_id now carries a provider namespace (spec §4-3): `facility:nmc:C1110693`
-- or `facility:hira:<base64url(ykiho)>`. The old comment described a bare hpid/ykiho.
ALTER TABLE favorite_facility
    MODIFY COLUMN facility_id VARCHAR(120) NOT NULL
        COMMENT 'Provider-namespaced id, e.g. facility:nmc:C1110693. Never a name.';
