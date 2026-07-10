package com.mermaid.profile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mermaid.common.ApiException;
import com.mermaid.facility.domain.FacilityType;
import com.mermaid.profile.domain.MatchConfidence;
import com.mermaid.profile.dto.ProfileDtos.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

/**
 * Profile CRUD against a real persistence context.
 *
 * <p>These tests exist because there were none, and the gap cost us: {@code @EntityGraph} fetching
 * both {@code allergies} and {@code favorites} threw {@code MultipleBagFetchException} on every
 * single profile request. Nothing in the suite touched a repository, so the whole feature was broken
 * and green.
 *
 * <p>They also pin the promise of spec §2-5: allergies reach this database only with consent, and
 * withdrawing consent deletes them.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ProfileCrudTest {

    private static final String DEVICE = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";

    @Autowired ProfileService service;

    @Test
    @DisplayName("reading an unknown deviceId creates the profile — there is no signup (FR-01)")
    void readCreates() {
        ProfileResponse p = service.read(DEVICE);

        assertThat(p.deviceId()).isEqualTo(DEVICE);
        assertThat(p.rememberAllergies()).isFalse();
        assertThat(p.allergies()).isEmpty();
        assertThat(p.favorites()).isEmpty();
    }

    @Test
    @DisplayName("both collections load in one read — MultipleBagFetchException regression")
    void bothCollectionsLoad() {
        service.updateConsent(DEVICE, new ConsentUpdateRequest(true));
        service.addAllergy(DEVICE, new AllergyCreateRequest("Ibuprofen", null));
        service.addFavorite(
                DEVICE,
                new FavoriteCreateRequest("facility:nmc:C1110693", FacilityType.PHARMACY, "Hotel", null));

        ProfileResponse p = service.read(DEVICE);

        assertThat(p.allergies()).hasSize(1);
        assertThat(p.favorites()).hasSize(1);
    }

    @Nested
    @DisplayName("allergy memory is opt-in (spec §2-5)")
    class Consent {

        @Test
        @DisplayName("adding an allergy without consent is rejected")
        void requiresConsent() {
            assertThatThrownBy(() -> service.addAllergy(DEVICE, new AllergyCreateRequest("Ibuprofen", null)))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("opted in");
        }

        @Test
        @DisplayName("withdrawing consent deletes what was stored — forget, not hide")
        void withdrawingDeletes() {
            service.updateConsent(DEVICE, new ConsentUpdateRequest(true));
            service.addAllergy(DEVICE, new AllergyCreateRequest("Ibuprofen", null));
            assertThat(service.read(DEVICE).allergies()).hasSize(1);

            ProfileResponse after = service.updateConsent(DEVICE, new ConsentUpdateRequest(false));

            assertThat(after.rememberAllergies()).isFalse();
            assertThat(after.allergies()).isEmpty();
        }

        @Test
        @DisplayName("withdrawing consent keeps favorites — they are not allergies")
        void withdrawingKeepsFavorites() {
            service.updateConsent(DEVICE, new ConsentUpdateRequest(true));
            service.addFavorite(
                    DEVICE,
                    new FavoriteCreateRequest("facility:nmc:C1110693", FacilityType.PHARMACY, null, null));

            ProfileResponse after = service.updateConsent(DEVICE, new ConsentUpdateRequest(false));

            assertThat(after.favorites()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("allergies are normalised on the way in")
    class Normalisation {

        @Test
        @DisplayName("a reviewed synonym is stored as its canonical key")
        void synonymResolved() {
            service.updateConsent(DEVICE, new ConsentUpdateRequest(true));

            AllergyResponse a = service.addAllergy(DEVICE, new AllergyCreateRequest("Paracetamol 500mg", null));

            assertThat(a.ingredientNameEn()).isEqualTo("Paracetamol 500mg"); // what they typed
            assertThat(a.normalizedKey()).isEqualTo("acetaminophen"); // what we compare
            assertThat(a.confidence()).isEqualTo(MatchConfidence.SYNONYM);
            assertThat(a.confidence().canBlock()).isTrue();
        }

        @Test
        @DisplayName("an already-canonical name is EXACT")
        void exactMatch() {
            service.updateConsent(DEVICE, new ConsentUpdateRequest(true));

            AllergyResponse a = service.addAllergy(DEVICE, new AllergyCreateRequest("ibuprofen", null));

            assertThat(a.confidence()).isEqualTo(MatchConfidence.EXACT);
        }
    }

    @Nested
    @DisplayName("favorites — the CRUD the course grades")
    class Favorites {

        @Test
        @DisplayName("create, read, update, delete")
        void fullCycle() {
            FavoriteResponse created =
                    service.addFavorite(
                            DEVICE,
                            new FavoriteCreateRequest(
                                    "facility:nmc:C1110693", FacilityType.PHARMACY, "Near my hotel", "Ask for English"));

            assertThat(created.alias()).isEqualTo("Near my hotel");
            assertThat(service.read(DEVICE).favorites()).hasSize(1);

            FavoriteResponse updated =
                    service.updateFavorite(
                            DEVICE, created.id(), new FavoriteUpdateRequest("Best pharmacy", "Open till 9pm"));
            assertThat(updated.alias()).isEqualTo("Best pharmacy");
            assertThat(updated.memo()).isEqualTo("Open till 9pm");

            service.deleteFavorite(DEVICE, created.id());
            assertThat(service.read(DEVICE).favorites()).isEmpty();
        }

        @Test
        @DisplayName("the id keeps its provider namespace, never a bare hpid")
        void namespacedId() {
            FavoriteResponse f =
                    service.addFavorite(
                            DEVICE,
                            new FavoriteCreateRequest("facility:nmc:C1110693", FacilityType.PHARMACY, null, null));

            assertThat(f.facilityId()).startsWith("facility:nmc:");
        }
    }

    @Test
    @DisplayName("country updates for FR-05 guidance")
    void updateCountry() {
        assertThat(service.updateCountry(DEVICE, new CountryUpdateRequest("AU")).countryCode())
                .isEqualTo("AU");
    }
}
