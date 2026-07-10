package com.mermaid.profile.dto;

import com.mermaid.facility.domain.FacilityType;
import com.mermaid.profile.domain.UserProfile;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request and response shapes for the profile CRUD endpoints. */
public final class ProfileDtos {

    private ProfileDtos() {}

    public record ProfileResponse(
            String deviceId,
            String countryCode,
            List<AllergyResponse> allergies,
            List<FavoriteResponse> favorites) {

        public static ProfileResponse from(UserProfile p) {
            return new ProfileResponse(
                    p.getDeviceId(),
                    p.getCountryCode(),
                    p.getAllergies().stream().map(AllergyResponse::from).toList(),
                    p.getFavorites().stream().map(FavoriteResponse::from).toList());
        }
    }

    public record AllergyResponse(Long id, String ingredientNameEn, String ingredientNameKo) {
        static AllergyResponse from(com.mermaid.profile.domain.AllergyIngredient a) {
            return new AllergyResponse(a.getId(), a.getIngredientNameEn(), a.getIngredientNameKo());
        }
    }

    public record FavoriteResponse(
            Long id, String facilityId, FacilityType facilityType, String memo) {
        static FavoriteResponse from(com.mermaid.profile.domain.FavoriteFacility f) {
            return new FavoriteResponse(
                    f.getId(), f.getFacilityId(), f.getFacilityType(), f.getMemo());
        }
    }

    /** CREATE — an ingredient to avoid. Matched against MAIN_INGR_ENG. */
    public record AllergyCreateRequest(
            @NotBlank @Size(max = 255) String ingredientNameEn, @Size(max = 255) String ingredientNameKo) {}

    /** CREATE — save a pharmacy or hospital. */
    public record FavoriteCreateRequest(
            @NotBlank @Size(max = 100) String facilityId,
            @NotNull FacilityType facilityType,
            @Size(max = 500) String memo) {}

    /** UPDATE — the memo is the only mutable part of a favorite. */
    public record FavoriteUpdateRequest(@Size(max = 500) String memo) {}

    /** UPDATE — nationality drives FR-05 guidance. */
    public record CountryUpdateRequest(@Size(min = 2, max = 2) String countryCode) {}
}
