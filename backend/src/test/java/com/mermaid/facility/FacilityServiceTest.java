package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;

import com.mermaid.common.SourceRef;
import com.mermaid.facility.domain.DutyTable;
import com.mermaid.facility.domain.FacilityType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
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
        var service =
                new FacilityService(
                        client,
                        new HospitalApiClient(null, null, null, null),
                        new HospitalDetailApiClient(null, null, null, null),
                        new HolidayCalendar(),
                        FRIDAY_AFTERNOON);

        var found = service.findNearby(37.5663, 126.9779, 1000, false, FacilityType.PHARMACY);

        assertThat(found).hasSize(1);
        assertThat(found.get(0).id()).isEqualTo("facility:nmc:near");
        assertThat(client.weeklyHoursRequests).isEqualTo(1);
    }

    private static PharmacyApiClient.RawPharmacy pharmacy(String hpid, double distanceKm) {
        return new PharmacyApiClient.RawPharmacy(
                hpid,
                hpid + " pharmacy",
                "Seoul",
                "02-000-0000",
                37.5663,
                126.9779,
                distanceKm,
                "0900",
                "1900");
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
}
