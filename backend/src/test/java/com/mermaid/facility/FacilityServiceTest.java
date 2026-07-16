package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;

import com.mermaid.common.PublicApiException;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import com.mermaid.facility.domain.DutyTable;
import com.mermaid.facility.domain.Facility;
import com.mermaid.facility.domain.FacilityOperation;
import com.mermaid.facility.domain.FacilityOperationPreference;
import com.mermaid.facility.domain.FacilityType;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class FacilityServiceTest {

    private static final Clock FRIDAY_AFTERNOON =
            Clock.fixed(Instant.parse("2026-07-10T05:00:00Z"), ZoneId.of("UTC"));

    private static final Clock CHILDRENS_DAY_AFTERNOON =
            Clock.fixed(Instant.parse("2026-05-05T05:00:00Z"), ZoneId.of("UTC"));

    private static final Instant HIRA_DIRECTORY_RETRIEVED_AT =
            Instant.parse("2026-07-09T00:00:00Z");

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
                        new EmergencyRoomApiClient(null, null, null, null),
                        new HolidayCalendar(date -> false),
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
                        .mapToObj(i -> pharmacy("closed-" + i, 0.001 + i * 0.001, "0100", "0200"))
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
    void openOrUnknownLooksPastClosedRowsAndOrdersConfirmedOpenBeforeUnknown() {
        List<PharmacyApiClient.RawPharmacy> closed =
                IntStream.range(0, 50)
                        .mapToObj(i -> pharmacy("closed-" + i, 0.001 + i * 0.001, "0100", "0200"))
                        .toList();
        var unknown = pharmacy("unknown-51", 0.051, null, null);
        var fartherOpen = pharmacy("open-52", 0.052, "0900", "1900");
        var client =
                new CountingPharmacyClient(
                        java.util.stream.Stream.concat(
                                        closed.stream(),
                                        java.util.stream.Stream.of(unknown, fartherOpen))
                                .toList()) {
                    @Override
                    public DutyTable weeklyHours(String hpid) {
                        weeklyHoursRequests.incrementAndGet();
                        if ("unknown-51".equals(hpid)) {
                            throw new PublicApiException("weekly hours unavailable");
                        }
                        return DutyTable.empty(SourceRef.DataMode.LIVE);
                    }
                };
        var service = pharmacyService(client);

        var found = service.findNearby(
                37.5663,
                126.9779,
                1000,
                FacilityOperationPreference.OPEN_OR_UNKNOWN,
                FacilityType.PHARMACY,
                2);

        assertThat(found)
                .extracting(Facility::id)
                .containsExactly("facility:nmc:open-52", "facility:nmc:unknown-51");
        assertThat(found).extracting(f -> f.operation().isOpenNow()).containsExactly(true, null);
        assertThat(client.weeklyHoursRequests).hasValue(52);
    }

    @Test
    void openOrUnknownExcludesConfirmedClosedRowsEvenWhenTheLimitHasRoom() {
        var client =
                new CountingPharmacyClient(
                        List.of(
                                pharmacy("closed", 0.001, "0100", "0200"),
                                pharmacy("unknown", 0.002, null, null),
                                pharmacy("open", 0.003, "0900", "1900")));
        var service = pharmacyService(client);

        var found = service.findNearby(
                37.5663,
                126.9779,
                1000,
                FacilityOperationPreference.OPEN_OR_UNKNOWN,
                FacilityType.PHARMACY,
                3);

        assertThat(found)
                .extracting(Facility::id)
                .containsExactly("facility:nmc:open", "facility:nmc:unknown");
        assertThat(found).noneMatch(facility -> Boolean.FALSE.equals(facility.operation().isOpenNow()));
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
                                .mapToObj(i -> pharmacy("pharmacy-" + i, 0.001 + i * 0.001, "0100", "0200"))
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

    @Test
    void officialHolidayCalendarSelectsThePharmacyHolidayRow() {
        var client =
                new CountingPharmacyClient(List.of(pharmacy("holiday-pharmacy", 0.1))) {
                    @Override
                    public DutyTable weeklyHours(String hpid) {
                        return new DutyTable(
                                Map.of(2, List.of("0900", "1900")), SourceRef.DataMode.LIVE);
                    }
                };
        var service =
                new FacilityService(
                        client,
                        new HospitalApiClient(null, null, null, null),
                        new HospitalDetailApiClient(null, null, null, null),
                        new EmergencyRoomApiClient(null, null, null, null),
                        officialFixtureCalendar(),
                        CHILDRENS_DAY_AFTERNOON);

        Facility found =
                service.findNearby(37.5663, 126.9779, 1000, false, FacilityType.PHARMACY).getFirst();

        // 2026-05-05 is Tuesday and the weekday row is open; only the official calendar makes the
        // absent holiday row win. Replacing the calendar predicate with false turns this red.
        assertThat(found.operation().isOpenNow()).isFalse();
        assertThat(found.operation().statusConfidence())
                .isEqualTo(FacilityOperation.StatusConfidence.OFFICIAL_SCHEDULE);
    }

    @Test
    void hiraPrimaryKeepsProviderIdentityAndUsesHiraDetailHours() {
        String ykiho =
                "JDQ4MTg4MSM1MSMkMSMkNCMkMDMkMzgxMTkxIzIxIyQxIyQ1IyQ4OSQzNjE0ODEjNzEjJDEjJDgjJDgz";
        var pharmacyClient = new HiraPharmacyClient(List.of(pharmacy(ykiho, 0.1, null, null)));
        var detailClient = new HiraPharmacyDetailClient();
        var service =
                new FacilityService(
                        pharmacyClient,
                        new HospitalApiClient(null, null, null, null),
                        detailClient,
                        new EmergencyRoomApiClient(null, null, null, null),
                        new HolidayCalendar(date -> false),
                        FRIDAY_AFTERNOON);

        Facility found =
                service.findNearby(37.5663, 126.9779, 1000, false, FacilityType.PHARMACY).getFirst();

        String recordSegment =
                Facility.urlSafeSegment(ykiho)
                        + "."
                        + Facility.urlSafeSegment(ykiho + " pharmacy");
        assertThat(found.id())
                .isEqualTo("facility:hira-pharmacy:" + recordSegment);
        assertThat(found.source().provider()).isEqualTo("건강보험심사평가원 약국정보서비스");
        assertThat(found.source().recordId()).isEqualTo(ykiho);
        assertThat(found.source().retrievedAt()).isEqualTo(HIRA_DIRECTORY_RETRIEVED_AT);
        assertThat(found.operation().isOpenNow()).isTrue();
        assertThat(found.operation().statusConfidence())
                .isEqualTo(FacilityOperation.StatusConfidence.OFFICIAL_SCHEDULE);
        assertThat(detailClient.requestedYkiho).isEqualTo(ykiho);
        assertThat(pharmacyClient.requestedRadiusMeters).isEqualTo(1000);
        assertThat(pharmacyClient.weeklyHoursRequests).hasValue(0);

        Facility detail = service.detail(found.id());
        assertThat(detail.nameKo()).isEqualTo(found.nameKo());
        assertThat(detail.addressKo()).isEqualTo(found.addressKo());
        assertThat(detail.source().recordId()).isEqualTo(ykiho);
        assertThat(detail.source().retrievedAt()).isEqualTo(HIRA_DIRECTORY_RETRIEVED_AT);
    }

    @Test
    void openNowKeepsTheNmcHpidWeeklyHoursPathWhenHiraHasNoSchedule() {
        var pharmacyClient = new ProviderAwareOpenNowPharmacyClient();
        var detailClient = new EmptyHiraPharmacyDetailClient();
        var service =
                new FacilityService(
                        pharmacyClient,
                        new HospitalApiClient(null, null, null, null),
                        detailClient,
                        new EmergencyRoomApiClient(null, null, null, null),
                        new HolidayCalendar(date -> false),
                        FRIDAY_AFTERNOON);

        List<Facility> found =
                service.findNearby(37.5663, 126.9779, 1000, true, FacilityType.PHARMACY, 1);

        assertThat(found).singleElement().satisfies(pharmacy -> {
            assertThat(pharmacy.id()).isEqualTo("facility:nmc:C1110693");
            assertThat(pharmacy.operation().isOpenNow()).isTrue();
            assertThat(pharmacy.operation().statusConfidence())
                    .isEqualTo(FacilityOperation.StatusConfidence.OFFICIAL_SCHEDULE);
        });
        assertThat(pharmacyClient.regularDirectoryRequests).hasValue(0);
        assertThat(pharmacyClient.openNowDirectoryRequests).hasValue(1);
        assertThat(pharmacyClient.weeklyHoursHpids).containsExactly("C1110693");
        assertThat(detailClient.requests).hasValue(0);
    }

    private static FacilityService pharmacyService(PharmacyApiClient client) {
        return new FacilityService(
                client,
                new HospitalApiClient(null, null, null, null),
                new HospitalDetailApiClient(null, null, null, null),
                new EmergencyRoomApiClient(null, null, null, null),
                new HolidayCalendar(date -> false),
                FRIDAY_AFTERNOON);
    }

    private static HolidayCalendar officialFixtureCalendar() {
        return new HolidayCalendar(
                new HolidayApiClient(
                        null,
                        new PublicApiProperties(
                                "", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x"),
                        new DataModeProperties(DataModeProperties.DataMode.FIXTURE)));
    }

    @Test
    void retainsDirectoryRowsWhenOneWeeklyHoursLookupFails() {
        var client = new PartiallyFailingPharmacyClient();
        var service = pharmacyService(client);

        List<Facility> found =
                service.findNearby(37.5663, 126.9779, 1000, false, FacilityType.PHARMACY);

        Facility failed = facility(found, ":failed");
        Facility failedWithDirectoryHours = facility(found, ":failed-with-directory-hours");
        Facility inferred = facility(found, ":no-weekly-schedule");
        Facility official = facility(found, ":official");
        assertThat(found).hasSize(4);

        // A failed lookup whose directory row has no usable times is UNKNOWN, never CLOSED (§2-3),
        // yet its live-sourced location survives.
        assertThat(failed.operation().isOpenNow()).isNull();
        assertThat(failed.operation().status()).isEqualTo(FacilityOperation.OperationStatus.UNKNOWN);
        assertThat(failed.source().dataMode()).isEqualTo(SourceRef.DataMode.LIVE);

        // A failed lookup cannot use partial directory hours to call a pharmacy open or closed (§2-3).
        assertThat(failedWithDirectoryHours.operation().isOpenNow()).isNull();
        assertThat(failedWithDirectoryHours.operation().status())
                .isEqualTo(FacilityOperation.OperationStatus.UNKNOWN);

        // An empty timetable returned successfully is different from a failed lookup: the directory
        // hours remain usable, but are explicitly marked as inferred rather than official.
        assertThat(inferred.operation().isOpenNow()).isTrue();
        assertThat(inferred.operation().statusConfidence())
                .isEqualTo(FacilityOperation.StatusConfidence.INFERRED);

        // The pharmacy whose lookup succeeded was still fully processed off its weekly table.
        assertThat(official.operation().isOpenNow()).isTrue();
        assertThat(official.operation().statusConfidence())
                .isEqualTo(FacilityOperation.StatusConfidence.OFFICIAL_SCHEDULE);

        // Mutation check: remove weeklyHoursLookupFailed's UNKNOWN guard and this test fails because
        // the row with directory hours is inferred open rather than reported as hours unknown.
        // Replacing operationOf's normal empty-table inference with UNKNOWN also fails this test.
    }

    private static Facility facility(List<Facility> found, String idSuffix) {
        return found.stream().filter(f -> f.id().endsWith(idSuffix)).findFirst().orElseThrow();
    }

    @Test
    void emergencyDistanceIsRecomputedForEachCallerFromSharedListCoordinates() {
        var emergencyClient =
                new CountingEmergencyRoomClient(
                        List.of(
                                new EmergencyRoomApiClient.RawEmergencyRoom(
                                        "A1100006",
                                        "Emergency hospital",
                                        "Seoul",
                                        "02-000-0000",
                                        37.5763,
                                        126.9779)));
        var service =
                new FacilityService(
                        null,
                        new HospitalApiClient(null, null, null, null),
                        new HospitalDetailApiClient(null, null, null, null),
                        emergencyClient,
                        new HolidayCalendar(date -> false),
                        FRIDAY_AFTERNOON);

        Facility first =
                service.findNearby(37.5663, 126.9779, 2_000, false, FacilityType.EMERGENCY_ROOM).get(0);
        Facility second =
                service.findNearby(37.5673, 126.9779, 2_000, false, FacilityType.EMERGENCY_ROOM).get(0);

        // Both callers must receive their own Haversine distance, which changes as their coordinates
        // change even though they reuse the same list row.
        assertThat(first.distanceMeters()).isGreaterThan(1_000.0);
        assertThat(second.distanceMeters()).isGreaterThan(900.0).isLessThan(first.distanceMeters());
    }

    @Test
    void emergencyRoomSearchHonoursThePublicResultLimit() {
        var emergencyClient =
                new CountingEmergencyRoomClient(
                        List.of(
                                new EmergencyRoomApiClient.RawEmergencyRoom(
                                        "near", "Near ER", "Seoul", null, 37.5664, 126.9779),
                                new EmergencyRoomApiClient.RawEmergencyRoom(
                                        "far", "Far ER", "Seoul", null, 37.5763, 126.9779)));
        var service =
                new FacilityService(
                        null,
                        new HospitalApiClient(null, null, null, null),
                        new HospitalDetailApiClient(null, null, null, null),
                        emergencyClient,
                        new HolidayCalendar(date -> false),
                        FRIDAY_AFTERNOON);

        var found =
                service.findNearby(37.5663, 126.9779, 2_000, false, FacilityType.EMERGENCY_ROOM, 1);

        assertThat(found).singleElement().extracting(Facility::id).isEqualTo("facility:nmc-emergency:near");
    }

    @Test
    void openOrUnknownKeepsEmergencyRoomsWhileConfirmedOpenOnlyDoesNotGuess() {
        var emergencyClient =
                new CountingEmergencyRoomClient(
                        List.of(new EmergencyRoomApiClient.RawEmergencyRoom(
                                "near", "Near ER", "Seoul", null, 37.5664, 126.9779)));
        var service =
                new FacilityService(
                        null,
                        new HospitalApiClient(null, null, null, null),
                        new HospitalDetailApiClient(null, null, null, null),
                        emergencyClient,
                        new HolidayCalendar(date -> false),
                        FRIDAY_AFTERNOON);

        var openOrUnknown = service.findNearby(
                37.5663,
                126.9779,
                2_000,
                FacilityOperationPreference.OPEN_OR_UNKNOWN,
                FacilityType.EMERGENCY_ROOM,
                1);
        var confirmedOnly = service.findNearby(
                37.5663,
                126.9779,
                2_000,
                FacilityOperationPreference.CONFIRMED_OPEN_ONLY,
                FacilityType.EMERGENCY_ROOM,
                1);

        assertThat(openOrUnknown)
                .singleElement()
                .satisfies(facility -> assertThat(facility.operation().isOpenNow()).isNull());
        assertThat(confirmedOnly).isEmpty();
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
                37.5663 + distanceKm / 111.32,
                126.9779,
                distanceKm,
                startTime,
                endTime);
    }

    private static class CountingPharmacyClient extends PharmacyApiClient {

        protected List<RawPharmacy> pharmacies;
        protected final AtomicInteger weeklyHoursRequests = new AtomicInteger();
        protected int requestedRadiusMeters;

        private CountingPharmacyClient(List<RawPharmacy> pharmacies) {
            super(null, null, null, null);
            this.pharmacies = pharmacies;
        }

        @Override
        public PharmacyBatch findNear(double lat, double lng, int radiusMeters) {
            requestedRadiusMeters = radiusMeters;
            return new PharmacyBatch(pharmacies, SourceRef.DataMode.LIVE);
        }

        @Override
        public PharmacyBatch findNearForOpenNow(double lat, double lng, int radiusMeters) {
            return findNear(lat, lng, radiusMeters);
        }

        @Override
        public DutyTable weeklyHours(String hpid) {
            weeklyHoursRequests.incrementAndGet();
            return DutyTable.empty(SourceRef.DataMode.LIVE);
        }
    }

    private static final class PartiallyFailingPharmacyClient extends PharmacyApiClient {

        private PartiallyFailingPharmacyClient() {
            super(null, null, null, null);
        }

        @Override
        public PharmacyBatch findNear(double lat, double lng, int radiusMeters) {
            return new PharmacyBatch(
                    List.of(
                            pharmacy("failed", 0.1, null, null),
                            pharmacy("failed-with-directory-hours", 0.15, "0900", "1900"),
                            pharmacy("no-weekly-schedule", 0.2, "0900", "1900"),
                            pharmacy("official", 0.25, "0900", "1900")),
                    SourceRef.DataMode.LIVE);
        }

        @Override
        public DutyTable weeklyHours(String hpid) {
            if (hpid.startsWith("failed")) {
                throw new PublicApiException("weekly hours unavailable");
            }
            if ("no-weekly-schedule".equals(hpid)) {
                return DutyTable.empty(SourceRef.DataMode.LIVE);
            }
            return new DutyTable(Map.of(5, List.of("0900", "1900")), SourceRef.DataMode.LIVE);
        }
    }

    private static final class HiraPharmacyClient extends CountingPharmacyClient {

        private HiraPharmacyClient(List<RawPharmacy> pharmacies) {
            super(pharmacies);
        }

        @Override
        public PharmacyBatch findNear(double lat, double lng, int radiusMeters) {
            requestedRadiusMeters = radiusMeters;
            return new PharmacyBatch(
                    pharmacies,
                    SourceRef.DataMode.LIVE,
                    PharmacyProvider.HIRA,
                    HIRA_DIRECTORY_RETRIEVED_AT);
        }

        @Override
        public HiraIdentityBatch hiraIdentity(String ykiho, String name) {
            RawPharmacy match =
                    pharmacies.stream()
                            .filter(row -> ykiho.equals(row.hpid()) && name.equals(row.name()))
                            .findFirst()
                            .orElse(null);
            return new HiraIdentityBatch(
                    match, SourceRef.DataMode.LIVE, HIRA_DIRECTORY_RETRIEVED_AT);
        }
    }

    private static final class HiraPharmacyDetailClient extends HospitalDetailApiClient {

        private String requestedYkiho;

        private HiraPharmacyDetailClient() {
            super(null, null, null, null);
        }

        @Override
        public HospitalDetailBatch findByYkiho(String ykiho) {
            requestedYkiho = ykiho;
            return new HospitalDetailBatch(
                    new HospitalDetail(
                            ykiho,
                            Map.of(5, List.of("0900", "1900")),
                            Optional.empty(),
                            false,
                            false,
                            null,
                            null),
                    SourceRef.DataMode.LIVE,
                    FRIDAY_AFTERNOON.instant());
        }
    }

    private static final class ProviderAwareOpenNowPharmacyClient extends PharmacyApiClient {

        private final AtomicInteger regularDirectoryRequests = new AtomicInteger();
        private final AtomicInteger openNowDirectoryRequests = new AtomicInteger();
        private final List<String> weeklyHoursHpids = new java.util.concurrent.CopyOnWriteArrayList<>();

        private ProviderAwareOpenNowPharmacyClient() {
            super(null, null, null, null);
        }

        @Override
        public PharmacyBatch findNear(double lat, double lng, int radiusMeters) {
            regularDirectoryRequests.incrementAndGet();
            return new PharmacyBatch(
                    List.of(pharmacy("HIRA-YKIHO", 0.1, null, null)),
                    SourceRef.DataMode.LIVE,
                    PharmacyProvider.HIRA,
                    HIRA_DIRECTORY_RETRIEVED_AT);
        }

        @Override
        public PharmacyBatch findNearForOpenNow(double lat, double lng, int radiusMeters) {
            openNowDirectoryRequests.incrementAndGet();
            return new PharmacyBatch(
                    List.of(pharmacy("C1110693", 0.1, null, null)),
                    SourceRef.DataMode.LIVE,
                    PharmacyProvider.NMC,
                    HIRA_DIRECTORY_RETRIEVED_AT);
        }

        @Override
        public DutyTable weeklyHours(String hpid) {
            weeklyHoursHpids.add(hpid);
            return new DutyTable(
                    Map.of(5, List.of("0900", "1900")), SourceRef.DataMode.LIVE);
        }
    }

    private static final class EmptyHiraPharmacyDetailClient extends HospitalDetailApiClient {

        private final AtomicInteger requests = new AtomicInteger();

        private EmptyHiraPharmacyDetailClient() {
            super(null, null, null, null);
        }

        @Override
        public HospitalDetailBatch findByYkiho(String ykiho) {
            requests.incrementAndGet();
            return new HospitalDetailBatch(
                    new HospitalDetail(
                            ykiho,
                            Map.of(),
                            Optional.empty(),
                            false,
                            false,
                            null,
                            null),
                    SourceRef.DataMode.LIVE,
                    FRIDAY_AFTERNOON.instant());
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

    private static final class CountingEmergencyRoomClient extends EmergencyRoomApiClient {

        private final List<RawEmergencyRoom> emergencyRooms;

        private CountingEmergencyRoomClient(List<RawEmergencyRoom> emergencyRooms) {
            super(null, null, null, null);
            this.emergencyRooms = emergencyRooms;
        }

        @Override
        public EmergencyRoomBatch findNear(double lat, double lng) {
            return new EmergencyRoomBatch(emergencyRooms, SourceRef.DataMode.LIVE);
        }
    }
}
