package com.mermaid.profile.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/**
 * An anonymous profile, keyed by a UUID the browser makes on first visit.
 *
 * <p>This is how "no login" (FR-01) and "the profile's avoided ingredients" (FR-04) coexist — see
 * spec §2-5. It is not an account: there is no password, no email, nothing that identifies a person.
 *
 * <p>Medical consultation history is <b>not</b> here and must not be added. It stays in the
 * browser's LocalStorage (spec §2-4).
 */
@Entity
@Table(name = "user_profile")
@EntityListeners(AuditingEntityListener.class)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class UserProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "device_id", nullable = false, unique = true, length = 36)
    private String deviceId;

    /** ISO 3166-1 alpha-2. Drives the country-specific guidance in FR-05. */
    @Column(name = "country_code", length = 2)
    private String countryCode;

    /**
     * Opt-in, default off (spec §2-5).
     *
     * <p>Allergies are a session input unless the user asks us to keep them. While this is false the
     * {@link #allergies} list must stay empty — a promise {@link #forgetAllergies()} enforces.
     */
    @Column(name = "remember_allergies", nullable = false)
    private boolean rememberAllergies = false;

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    private final List<AllergyIngredient> allergies = new ArrayList<>();

    @OneToMany(mappedBy = "profile", cascade = CascadeType.ALL, orphanRemoval = true)
    private final List<FavoriteFacility> favorites = new ArrayList<>();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public UserProfile(String deviceId) {
        this.deviceId = deviceId;
    }

    public void changeCountry(String countryCode) {
        this.countryCode = countryCode;
    }

    public boolean remembersAllergies() {
        return rememberAllergies;
    }

    /** Turning memory off is not a preference change; it is a deletion. */
    public void setRememberAllergies(boolean remember) {
        this.rememberAllergies = remember;
        if (!remember) {
            forgetAllergies();
        }
    }

    public void forgetAllergies() {
        allergies.clear(); // orphanRemoval deletes the rows
    }

    /**
     * @throws IllegalStateException when the user has not opted in — storing an allergy they did not
     *     ask us to keep would break the promise this column exists to make
     */
    public void addAllergy(AllergyIngredient ingredient) {
        if (!rememberAllergies) {
            throw new IllegalStateException(
                    "profile has not opted in to remembering allergies (spec §2-5)");
        }
        allergies.add(ingredient);
        ingredient.assignTo(this);
    }

    public void addFavorite(FavoriteFacility favorite) {
        favorites.add(favorite);
        favorite.assignTo(this);
    }
}
