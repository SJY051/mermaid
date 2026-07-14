package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;

import com.mermaid.common.PublicApiException;
import com.mermaid.common.SourceRef;
import com.mermaid.facility.domain.DutyTable;
import com.mermaid.facility.domain.Facility;
import com.mermaid.facility.domain.FacilityOperation;
import com.mermaid.facility.domain.FacilityType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FacilityServiceTest {

    private static final Clock FRIDAY_AFTERNOON =
            Clock.fixed(Instant.parse("2026-07-10T05:00:00Z"), ZoneId.of("UTC"));

    @Test
    void excludesOutOfRadiusPharmaciesBeforeLoadingTheirWeeklyHours() {
        var client =
                new CountingPharmacyClient(
                        List.of(
                                pharmacy("far", 10.0),
                                pharmacy("near", 0.1)));
        var service = new FacilityService(client, new HolidayCalendar(), FRIDAY_AFTERNOON);

        var found = service.findNearby(37.5663, 126.9779, 1000, false, FacilityType.PHARMACY);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).id()).isEqualTo("facility:nmc:near");
        assertThat(client.weeklyHoursRequests).isEqualTo(1);
    }

    @Test
    void retainsDirectoryRowsWhenOneWeeklyHoursLookupFails() {
        var client = new PartiallyFailingPharmacyClient();
        var service = new FacilityService(client, new HolidayCalendar(), FRIDAY_AFTERNOON);

        List<Facility> found =
                service.findNearby(37.5663, 126.9779, 1000, false, FacilityType.PHARMACY);

        Facility failed = facility(found, ":failed");
        Facility failedInferred = facility(found, ":failed-inferred");
        Facility official = facility(found, ":official");
        assertThat(found).hasSize(3);

        // A failed lookup whose directory row has no usable times is UNKNOWN, never CLOSED (§2-3),
        // yet its live-sourced location survives.
        assertThat(failed.operation().isOpenNow()).isNull();
        assertThat(failed.operation().status()).isEqualTo(FacilityOperation.OperationStatus.UNKNOWN);
        assertThat(failed.source().dataMode()).isEqualTo(SourceRef.DataMode.LIVE);

        // A failed lookup whose directory row does carry start/end falls back to INFERRED (FR-002).
        assertThat(failedInferred.operation().isOpenNow()).isTrue();
        assertThat(failedInferred.operation().statusConfidence())
                .isEqualTo(FacilityOperation.StatusConfidence.INFERRED);

        // The pharmacy whose lookup succeeded was still fully processed off its weekly table.
        assertThat(official.operation().isOpenNow()).isTrue();
        assertThat(official.operation().statusConfidence())
                .isEqualTo(FacilityOperation.StatusConfidence.OFFICIAL_SCHEDULE);

        // Mutation check: remove the PublicApiException catch in FacilityService#toFacility and this
        // test fails before an assertion because the failed timetable lookup escapes the service.
    }

    private static Facility facility(List<Facility> found, String idSuffix) {
        return found.stream().filter(f -> f.id().endsWith(idSuffix)).findFirst().orElseThrow();
    }

    private static PharmacyApiClient.RawPharmacy pharmacy(String hpid, double distanceKm) {
        return pharmacy(hpid, distanceKm, "0900", "1900");
    }

    private static PharmacyApiClient.RawPharmacy pharmacy(
            String hpid, double distanceKm, String startTime, String endTime) {
        return new PharmacyApiClient.RawPharmacy(
                hpid,
                hpid + " pharmacy",
                "Seoul",
                "02-000-0000",
                37.5663,
                126.9779,
                distanceKm,
                startTime,
                endTime);
    }

    private static final class CountingPharmacyClient extends PharmacyApiClient {

        private final List<RawPharmacy> pharmacies;
        private int weeklyHoursRequests;

        private CountingPharmacyClient(List<RawPharmacy> pharmacies) {
            super(null, null, null, null);
            this.pharmacies = pharmacies;
        }

        @Override
        public PharmacyBatch findNear(double lat, double lng) {
            return new PharmacyBatch(pharmacies, SourceRef.DataMode.LIVE);
        }

        @Override
        public DutyTable weeklyHours(String hpid) {
            weeklyHoursRequests++;
            return DutyTable.empty(SourceRef.DataMode.LIVE);
        }
    }

    private static final class PartiallyFailingPharmacyClient extends PharmacyApiClient {

        private PartiallyFailingPharmacyClient() {
            super(null, null, null, null);
        }

        @Override
        public PharmacyBatch findNear(double lat, double lng) {
            return new PharmacyBatch(
                    List.of(
                            pharmacy("failed", 0.1, null, null),
                            pharmacy("failed-inferred", 0.15, "0900", "1900"),
                            pharmacy("official", 0.2, "0900", "1900")),
                    SourceRef.DataMode.LIVE);
        }

        @Override
        public DutyTable weeklyHours(String hpid) {
            if (hpid.startsWith("failed")) {
                throw new PublicApiException("weekly hours unavailable");
            }
            return new DutyTable(Map.of(5, List.of("0900", "1900")), SourceRef.DataMode.LIVE);
        }
    }
}
