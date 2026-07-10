package com.mermaid.profile.domain;

import jakarta.persistence.*;
import java.time.Instant;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * An ingredient the user must avoid (FR-04).
 *
 * <p>{@code normalizedKey} is what we compare against {@code MAIN_INGR_ENG} from the 의약품 제품 허가정보
 * API — English, lower-cased, dose information stripped. e약은요 carries no ingredient data at all,
 * which is why that second API exists (spec §2-8).
 *
 * <p>A row only reaches this table when the user opted in ({@link UserProfile#remembersAllergies()}).
 * Otherwise the ingredient lives in the browser session and never touches the server.
 */
@Entity
@Table(name = "allergy_ingredient")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AllergyIngredient {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "profile_id", nullable = false)
    private UserProfile profile;

    /** As the user typed it. Kept so we can show them what they entered. */
    @Column(name = "ingredient_name_en", nullable = false)
    private String ingredientNameEn;

    @Column(name = "ingredient_name_ko")
    private String ingredientNameKo;

    /** What we actually match on. Null when normalisation failed. */
    @Column(name = "normalized_key")
    private String normalizedKey;

    /** Whether that normalisation is trustworthy enough to block a drug. */
    @Enumerated(EnumType.STRING)
    @Column(name = "confidence", nullable = false, length = 20)
    private MatchConfidence confidence = MatchConfidence.UNKNOWN;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AllergyIngredient(
            String ingredientNameEn,
            String ingredientNameKo,
            String normalizedKey,
            MatchConfidence confidence) {
        this.ingredientNameEn = ingredientNameEn;
        this.ingredientNameKo = ingredientNameKo;
        this.normalizedKey = normalizedKey;
        this.confidence = confidence == null ? MatchConfidence.UNKNOWN : confidence;
    }

    void assignTo(UserProfile profile) {
        this.profile = profile;
    }
}
