package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.cache.annotation.Cacheable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class EmergencyRoomApiClientTest {

    private EmergencyRoomApiClient fixtureClient() {
        var properties =
                new PublicApiProperties(
                        "",
                        "https://x",
                        "https://emergency.example",
                        "https://x",
                        "https://x",
                        "https://x",
                        "https://x",
                        "https://x");
        return new EmergencyRoomApiClient(
                null,
                properties,
                new DataModeProperties(DataModeProperties.DataMode.FIXTURE),
                new FixtureLoader(new ObjectMapper()));
    }

    @Test
    @DisplayName("reads NMC emergency-room location records from the captured fixture")
    void readsFixtureRecords() {
        var batch = fixtureClient().findNear(37.5663, 126.9779);

        assertThat(batch.origin()).isEqualTo(SourceRef.DataMode.FIXTURE);
        assertThat(batch.emergencyRooms())
                .extracting(EmergencyRoomApiClient.RawEmergencyRoom::hpid)
                .contains("A1100006", "A1100029");
    }

    @Test
    @DisplayName("requests the NMC emergency location operation with coordinates and _type=json")
    void buildsOfficialLocationUri() {
        URI uri = fixtureClient().uriFor(37.5663, 126.9779);

        assertThat(uri.getPath()).endsWith("/getEgytLcinfoInqire");
        assertThat(uri.getQuery()).contains("WGS84_LON=126.9779");
        assertThat(uri.getQuery()).contains("WGS84_LAT=37.5663");
        assertThat(uri.getQuery()).contains("_type=json");
    }

    @Test
    @DisplayName("nearby origins share a 100-m list cache key")
    void nearbyOriginsShareGridCacheKey() throws NoSuchMethodException {
        Cacheable cacheable =
                EmergencyRoomApiClient.class
                        .getMethod("findNear", double.class, double.class)
                        .getAnnotation(Cacheable.class);

        assertThat(cacheable.key()).contains("cacheKeyFor");
        assertThat(EmergencyRoomApiClient.cacheKeyFor(37.56631, 126.97791))
                .isEqualTo(EmergencyRoomApiClient.cacheKeyFor(37.56634, 126.97794));
        assertThat(EmergencyRoomApiClient.cacheKeyFor(37.56631, 126.97791))
                .isNotEqualTo(EmergencyRoomApiClient.cacheKeyFor(37.56680, 126.97791));
    }

    @Test
    @DisplayName("fetches the shared NMC list from the grid-cell centre")
    void fetchesFromGridCellCentre() {
        var fetchedLat = new AtomicReference<Double>();
        var fetchedLng = new AtomicReference<Double>();
        var client =
                new EmergencyRoomApiClient(
                        null,
                        new PublicApiProperties(
                                "decoding-key",
                                "https://x",
                                "https://emergency.example",
                                "https://x",
                                "https://x",
                                "https://x",
                                "https://x",
                                "https://x"),
                        new DataModeProperties(DataModeProperties.DataMode.LIVE),
                        new FixtureLoader(new ObjectMapper())) {
                    @Override
                    protected com.fasterxml.jackson.databind.JsonNode fetch(double lat, double lng) {
                        fetchedLat.set(lat);
                        fetchedLng.set(lng);
                        return new FixtureLoader(new ObjectMapper()).load("emergency_room.json");
                    }
                };

        client.findNear(37.565499, 126.977501);

        assertThat(fetchedLat.get()).isEqualTo(37.565);
        assertThat(fetchedLng.get()).isEqualTo(126.978);
    }

    @Test
    @DisplayName("drops rows that cannot be safely put on a map")
    void dropsUnaddressableRows() throws Exception {
        var response =
                new ObjectMapper()
                        .readTree(
                                """
                                {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":[
                                  {"hpid":"valid","latitude":"37.5","longitude":"126.9"},
                                  {"hpid":"","latitude":"37.5","longitude":"126.9"},
                                  {"hpid":"infinite","latitude":"Infinity","longitude":"126.9"}
                                ]}}}}
                                """);

        assertThat(fixtureClient().parse(response))
                .extracting(EmergencyRoomApiClient.RawEmergencyRoom::hpid)
                .containsExactly("valid");
    }
}
