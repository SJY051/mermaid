package com.mermaid.profile;

import com.mermaid.common.NotFoundException;
import com.mermaid.profile.domain.AllergyIngredient;
import com.mermaid.profile.domain.FavoriteFacility;
import com.mermaid.profile.domain.UserProfile;
import com.mermaid.profile.dto.ProfileDtos.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD over the anonymous profile (spec §4-3).
 *
 * <p>{@link #getOrCreate} is the reason there is no signup screen: the first request carrying a new
 * {@code deviceId} silently creates the row.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProfileService {

    private final ProfileRepository profileRepository;

    @Transactional
    public UserProfile getOrCreate(String deviceId) {
        return profileRepository
                .findByDeviceId(deviceId)
                .orElseGet(() -> profileRepository.save(new UserProfile(deviceId)));
    }

    public ProfileResponse read(String deviceId) {
        return ProfileResponse.from(getOrCreate(deviceId));
    }

    @Transactional
    public ProfileResponse updateCountry(String deviceId, CountryUpdateRequest request) {
        UserProfile profile = getOrCreate(deviceId);
        profile.changeCountry(request.countryCode());
        return ProfileResponse.from(profile);
    }

    @Transactional
    public AllergyResponse addAllergy(String deviceId, AllergyCreateRequest request) {
        UserProfile profile = getOrCreate(deviceId);
        AllergyIngredient ingredient =
                new AllergyIngredient(request.ingredientNameEn(), request.ingredientNameKo());
        profile.addAllergy(ingredient);
        profileRepository.flush(); // assign the id before we map it out
        return ProfileResponse.from(profile).allergies().stream()
                .filter(a -> a.ingredientNameEn().equals(request.ingredientNameEn()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("allergy vanished after flush"));
    }

    @Transactional
    public void deleteAllergy(String deviceId, Long allergyId) {
        UserProfile profile = getOrCreate(deviceId);
        boolean removed =
                profile.getAllergies().removeIf(a -> a.getId().equals(allergyId)); // orphanRemoval
        if (!removed) {
            throw new NotFoundException("No allergy " + allergyId + " on this profile");
        }
    }

    @Transactional
    public FavoriteResponse addFavorite(String deviceId, FavoriteCreateRequest request) {
        UserProfile profile = getOrCreate(deviceId);
        profile.addFavorite(
                new FavoriteFacility(request.facilityId(), request.facilityType(), request.memo()));
        profileRepository.flush();
        return ProfileResponse.from(profile).favorites().stream()
                .filter(f -> f.facilityId().equals(request.facilityId()))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("favorite vanished after flush"));
    }

    @Transactional
    public FavoriteResponse updateFavorite(
            String deviceId, Long favoriteId, FavoriteUpdateRequest request) {
        UserProfile profile = getOrCreate(deviceId);
        FavoriteFacility favorite =
                profile.getFavorites().stream()
                        .filter(f -> f.getId().equals(favoriteId))
                        .findFirst()
                        .orElseThrow(
                                () -> new NotFoundException("No favorite " + favoriteId + " on this profile"));
        favorite.changeMemo(request.memo());
        return ProfileResponse.from(profile).favorites().stream()
                .filter(f -> f.id().equals(favoriteId))
                .findFirst()
                .orElseThrow();
    }

    @Transactional
    public void deleteFavorite(String deviceId, Long favoriteId) {
        UserProfile profile = getOrCreate(deviceId);
        boolean removed = profile.getFavorites().removeIf(f -> f.getId().equals(favoriteId));
        if (!removed) {
            throw new NotFoundException("No favorite " + favoriteId + " on this profile");
        }
    }
}
