-- mermAid initial schema.
--
-- Scope note (docs/specs/001-foundation/spec.md §4):
--   Medical consultation CHAT HISTORY is deliberately NOT stored here.
--   It lives only in the browser's LocalStorage. Do not add a `chat_message`
--   table without revisiting §2-4 and §7 of the spec first.
--
--   Public-API response caching is Redis's job, not a table's.
--
-- These three tables are the source of the ERD and 테이블 명세서 deliverables.

-- An anonymous profile. There is no login (FR-01). The browser generates a
-- UUID on first visit, keeps it in LocalStorage, and sends it as a header.
CREATE TABLE user_profile (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    device_id    CHAR(36)     NOT NULL COMMENT 'Client-generated UUID. Not a user account.',
    country_code CHAR(2)      NULL     COMMENT 'ISO 3166-1 alpha-2, drives FR-05 guidance.',
    created_at   DATETIME(6)  NOT NULL,
    updated_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_profile_device_id (device_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Ingredients the user must avoid (FR-04).
-- `ingredient_name_en` is matched against MAIN_INGR_ENG from the
-- 의약품 제품 허가정보 API (e약은요 has no ingredient data — spec §2-8).
CREATE TABLE allergy_ingredient (
    id                 BIGINT       NOT NULL AUTO_INCREMENT,
    profile_id         BIGINT       NOT NULL,
    ingredient_name_en VARCHAR(255) NOT NULL,
    ingredient_name_ko VARCHAR(255) NULL,
    created_at         DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_allergy_profile_ingredient (profile_id, ingredient_name_en),
    CONSTRAINT fk_allergy_profile FOREIGN KEY (profile_id)
        REFERENCES user_profile (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;

-- Saved pharmacies / hospitals.
-- `facility_id` holds an `hpid` (pharmacy, from 국립중앙의료원) or a
-- `ykiho` (hospital, from 심평원). They never collide, so one column suffices.
CREATE TABLE favorite_facility (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    profile_id    BIGINT       NOT NULL,
    facility_id   VARCHAR(100) NOT NULL COMMENT 'hpid (pharmacy) or ykiho (hospital)',
    facility_type VARCHAR(20)  NOT NULL COMMENT 'PHARMACY | HOSPITAL',
    memo          VARCHAR(500) NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_favorite_profile_facility (profile_id, facility_id),
    CONSTRAINT fk_favorite_profile FOREIGN KEY (profile_id)
        REFERENCES user_profile (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
