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
 * <p>{@code ingredientNameEn} is matched against {@code MAIN_INGR_ENG} from the 의약품 제품 허가정보 API.
 * e약은요 carries no ingredient data at all, which is why that second API exists (spec §2-8).
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

    @Column(name = "ingredient_name_en", nullable = false)
    private String ingredientNameEn;

    @Column(name = "ingredient_name_ko")
    private String ingredientNameKo;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public AllergyIngredient(String ingredientNameEn, String ingredientNameKo) {
        this.ingredientNameEn = ingredientNameEn;
        this.ingredientNameKo = ingredientNameKo;
    }

    void assignTo(UserProfile profile) {
        this.profile = profile;
    }
}
