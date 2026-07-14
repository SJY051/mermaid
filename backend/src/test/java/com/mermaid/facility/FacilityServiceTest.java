package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;

import com.mermaid.common.SourceRef;
import com.mermaid.facility.domain.DutyTable;
import com.mermaid.facility.domain.FacilityType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
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
        assertThat(client.weeklyHoursRequests).hasValue(1);
    }

    @Test
    void openNowLooksPastTheReturnedLimitForAFartherOpenPharmacy() {
        List<PharmacyApiClient.RawPharmacy> closed =
                IntStream.range(0, 50)
                        .mapToObj(i -> pharmacy("closed-" + i, 0.001 + i * 0.001, "0000", "0001"))
                        .toList();
        var fartherOpen = pharmacy("open-51", 0.051, "0900", "1900");
        var client =
                new CountingPharmacyClient(
                        java.util.stream.Stream.concat(closed.stream(), java.util.stream.Stream.of(fartherOpen))
                                .toList());
        var service = pharmacyService(client);

        var found = service.findNearby(37.5663, 126.9779, 1000, true, FacilityType.PHARMACY, 1);

        assertThat(found).singleElement().extracting(f -> f.id()).isEqualTo("facility:nmc:open-51");
    }

    @Test
    void ordinaryPharmacySearchFetchesOnlyTheReturnedLimitOfWeeklyTables() {
        var client =
                new CountingPharmacyClient(
                        IntStream.range(0, 60)
                                .mapToObj(i -> pharmacy("pharmacy-" + i, 0.001 + i * 0.001))
                                .toList());
        var service = pharmacyService(client);

        var found = service.findNearby(37.5663, 126.9779, 1000, false, FacilityType.PHARMACY, 10);

        assertThat(found).hasSize(10);
        assertThat(client.weeklyHoursRequests).hasValue(10);
    }

    @Test
    void openNowPharmacySearchNeverFetchesMoreThanTheBoundedCandidateSet() {
        var client =
                new CountingPharmacyClient(
                        IntStream.range(0, 150)
                                .mapToObj(i -> pharmacy("pharmacy-" + i, 0.001 + i * 0.001, "0000", "0001"))
                                .toList());
        var service = pharmacyService(client);

        var found = service.findNearby(37.5663, 126.9779, 1000, true, FacilityType.PHARMACY, 1);

        assertThat(found).isEmpty();
        assertThat(client.weeklyHoursRequests).hasValue(100);
    }

    @Test
    void pharmacyWeeklyHoursFetchesUseBoundedConcurrency() {
        var client =
                new BlockingPharmacyClient(
                        IntStream.range(0, 8)
                                .mapToObj(i -> pharmacy("pharmacy-" + i, 0.001 + i * 0.001))
                                .toList());
        var service = pharmacyService(client);

        var found = service.findNearby(37.5663, 126.9779, 1000, false, FacilityType.PHARMACY, 8);

        assertThat(found).hasSize(8);
        assertThat(client.maximumConcurrentRequests).hasValue(4);
    }

    private static FacilityService pharmacyService(PharmacyApiClient client) {
        return new FacilityService(
                client,
                new HospitalApiClient(null, null, null, null),
                new HospitalDetailApiClient(null, null, null, null),
                new HolidayCalendar(),
                FRIDAY_AFTERNOON);
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

    private static class CountingPharmacyClient extends PharmacyApiClient {

        private List<RawPharmacy> pharmacies;
        private final AtomicInteger weeklyHoursRequests = new AtomicInteger();

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
            weeklyHoursRequests.incrementAndGet();
            return DutyTable.empty(SourceRef.DataMode.LIVE);
        }
    }

    private static final class BlockingPharmacyClient extends CountingPharmacyClient {

        private final AtomicInteger activeRequests = new AtomicInteger();
        private final AtomicInteger maximumConcurrentRequests = new AtomicInteger();
        private final CountDownLatch firstFourRequests = new CountDownLatch(4);

        private BlockingPharmacyClient(List<RawPharmacy> pharmacies) {
            super(pharmacies);
        }

        @Override
        public DutyTable weeklyHours(String hpid) {
            int active = activeRequests.incrementAndGet();
            maximumConcurrentRequests.accumulateAndGet(active, Math::max);
            firstFourRequests.countDown();
            try {
                firstFourRequests.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            } finally {
                activeRequests.decrementAndGet();
            }
            return super.weeklyHours(hpid);
        }
    }
}
