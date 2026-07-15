package com.mermaid.profile.dto;

import com.mermaid.facility.domain.FacilityType;
import com.mermaid.profile.domain.AllergyIngredient;
import com.mermaid.profile.domain.FavoriteFacility;
import com.mermaid.profile.domain.MatchConfidence;
import com.mermaid.profile.domain.UserProfile;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request and response shapes for the profile CRUD endpoints (spec §4-5). */
public final class ProfileDtos {

    private ProfileDtos() {}

    public record ProfileResponse(
            String deviceId,
            String countryCode,
            /** When false, {@code allergies} is empty by construction (spec §2-5). */
            boolean rememberAllergies,
            List<AllergyResponse> allergies,
            List<FavoriteResponse> favorites) {

        public static ProfileResponse from(UserProfile p) {
            return new ProfileResponse(
                    p.getDeviceId(),
                    p.getCountryCode(),
                    p.remembersAllergies(),
                    p.getAllergies().stream().map(AllergyResponse::from).toList(),
                    p.getFavorites().stream().map(FavoriteResponse::from).toList());
        }
    }

    public record AllergyResponse(
            Long id,
            String ingredientNameEn,
            String ingredientNameKo,
            String normalizedKey,
            /** Only {@code exact} and {@code synonym} may block a drug (spec §2-12). */
            MatchConfidence confidence) {

        static AllergyResponse from(AllergyIngredient a) {
            return new AllergyResponse(
                    a.getId(),
                    a.getIngredientNameEn(),
                    a.getIngredientNameKo(),
                    a.getNormalizedKey(),
                    a.getConfidence());
        }
    }

    public record FavoriteResponse(
            Long id, String facilityId, FacilityType facilityType, String alias, String memo) {

        static FavoriteResponse from(FavoriteFacility f) {
            return new FavoriteResponse(
                    f.getId(), f.getFacilityId(), f.getFacilityType(), f.getAlias(), f.getMemo());
        }
    }

    /** CREATE — an ingredient to avoid. Requires {@code rememberAllergies}. */
    public record AllergyCreateRequest(
            @NotBlank @Size(max = 255) String ingredientNameEn,
            @Size(max = 255) String ingredientNameKo) {}

    /** CREATE — save a pharmacy or hospital. {@code facilityId} must be provider-namespaced. */
    public record FavoriteCreateRequest(
            @NotBlank @Size(max = 255) String facilityId,
            @NotNull FacilityType facilityType,
            @Size(max = 100) String alias,
            @Size(max = 500) String memo) {}

    /** UPDATE — alias and memo are the only mutable parts of a favorite. */
    public record FavoriteUpdateRequest(@Size(max = 100) String alias, @Size(max = 500) String memo) {}

    /** UPDATE — nationality drives FR-05 guidance. */
    public record CountryUpdateRequest(@Size(min = 2, max = 2) String countryCode) {}

    /**
     * UPDATE — consent to remember allergies across sessions.
     *
     * <p>Setting it to false deletes the stored ingredients; it is not merely a display preference.
     */
    public record ConsentUpdateRequest(@NotNull Boolean rememberAllergies) {}
}
