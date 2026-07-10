package com.mermaid.profile;

import com.mermaid.profile.dto.ProfileDtos.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Anonymous profile CRUD (spec §4-3, §5).
 *
 * <p>{@code deviceId} is a UUID the browser generated — not an account. Anyone holding it can read
 * and edit that profile, which is acceptable only because nothing identifying is stored here.
 * Consultation history never touches the server.
 */
@RestController
@RequestMapping("/api/v1/profiles/{deviceId}")
@RequiredArgsConstructor
public class ProfileController {

    private final ProfileService profileService;

    /** READ */
    @GetMapping
    public ProfileResponse read(@PathVariable String deviceId) {
        return profileService.read(deviceId);
    }

    /** UPDATE — nationality (FR-05) */
    @PatchMapping
    public ProfileResponse updateCountry(
            @PathVariable String deviceId, @Valid @RequestBody CountryUpdateRequest request) {
        return profileService.updateCountry(deviceId, request);
    }

    /**
     * UPDATE — consent to remember allergies across sessions (spec §2-5).
     *
     * <p>Default off. Turning it off deletes what was stored, rather than hiding it.
     */
    @PatchMapping("/consent")
    public ProfileResponse updateConsent(
            @PathVariable String deviceId, @Valid @RequestBody ConsentUpdateRequest request) {
        return profileService.updateConsent(deviceId, request);
    }

    /** CREATE — an ingredient to avoid (FR-04). Requires consent above. */
    @PostMapping("/allergies")
    @ResponseStatus(HttpStatus.CREATED)
    public AllergyResponse addAllergy(
            @PathVariable String deviceId, @Valid @RequestBody AllergyCreateRequest request) {
        return profileService.addAllergy(deviceId, request);
    }

    /** DELETE — stop avoiding an ingredient */
    @DeleteMapping("/allergies/{allergyId}")
    public ResponseEntity<Void> deleteAllergy(
            @PathVariable String deviceId, @PathVariable Long allergyId) {
        profileService.deleteAllergy(deviceId, allergyId);
        return ResponseEntity.noContent().build();
    }

    /** CREATE — save a facility */
    @PostMapping("/favorites")
    @ResponseStatus(HttpStatus.CREATED)
    public FavoriteResponse addFavorite(
            @PathVariable String deviceId, @Valid @RequestBody FavoriteCreateRequest request) {
        return profileService.addFavorite(deviceId, request);
    }

    /** UPDATE — edit a saved facility's memo */
    @PatchMapping("/favorites/{favoriteId}")
    public FavoriteResponse updateFavorite(
            @PathVariable String deviceId,
            @PathVariable Long favoriteId,
            @Valid @RequestBody FavoriteUpdateRequest request) {
        return profileService.updateFavorite(deviceId, favoriteId, request);
    }

    /** DELETE — unsave a facility */
    @DeleteMapping("/favorites/{favoriteId}")
    public ResponseEntity<Void> deleteFavorite(
            @PathVariable String deviceId, @PathVariable Long favoriteId) {
        profileService.deleteFavorite(deviceId, favoriteId);
        return ResponseEntity.noContent().build();
    }
}
