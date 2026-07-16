package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.PublicApiException;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

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
    @DisplayName("nearby origins share a 100-m list cache key without crossing data modes")
    void nearbyOriginsShareGridCacheKeyWithoutCrossingDataModes() {
        var fixture = clientFor(DataModeProperties.DataMode.FIXTURE, "");
        var live = clientFor(DataModeProperties.DataMode.LIVE, "decoding-key");

        assertThat(fixture.cacheKeyFor(37.56631, 126.97791))
                .isEqualTo(fixture.cacheKeyFor(37.56634, 126.97794));
        assertThat(fixture.cacheKeyFor(37.56631, 126.97791))
                .isNotEqualTo(fixture.cacheKeyFor(37.56680, 126.97791));
        // Removing mode/origin from the key makes this assertion red and would let a fixture
        // response be served after a process switches to live mode against the same Redis.
        assertThat(fixture.cacheKeyFor(37.56631, 126.97791))
                .isNotEqualTo(live.cacheKeyFor(37.56631, 126.97791));
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

    @Test
    @DisplayName("a live WebClient failure never preserves request values or the service-key cause")
    void liveFailureDropsSecretCoordinatesAndCause() {
        String secret = "never-log-this-emergency-key";
        WebClient webClient =
                WebClient.builder()
                        .exchangeFunction(
                                request ->
                                        Mono.error(
                                                new IllegalStateException(
                                                        request.url()
                                                                + " upstream failed "
                                                                + secret
                                                                + " at 37.5663,126.9779")))
                        .build();
        Logger logger = (Logger) LoggerFactory.getLogger(EmergencyRoomApiClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThatThrownBy(
                            () ->
                                    clientFor(
                                                    webClient,
                                                    DataModeProperties.DataMode.LIVE,
                                                    secret)
                                            .findNear(37.5663, 126.9779))
                    .isInstanceOf(PublicApiException.class)
                    .hasMessage("Emergency-room lookup failed")
                    .hasNoCause()
                    .satisfies(error -> assertThat(error.getSuppressed()).isEmpty());
            assertThat(appender.list).isEmpty();

            EmergencyRoomApiClient.EmergencyRoomBatch fallback =
                    clientFor(webClient, DataModeProperties.DataMode.HYBRID, secret)
                            .findNear(37.5663, 126.9779);
            assertThat(fallback.origin()).isEqualTo(SourceRef.DataMode.FIXTURE);
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .allSatisfy(
                            message ->
                                    assertThat(message)
                                            .doesNotContain(secret, "serviceKey", "37.5663", "126.9779"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    private EmergencyRoomApiClient clientFor(
            DataModeProperties.DataMode mode, String serviceKey) {
        return clientFor(null, mode, serviceKey);
    }

    private EmergencyRoomApiClient clientFor(
            WebClient webClient, DataModeProperties.DataMode mode, String serviceKey) {
        return new EmergencyRoomApiClient(
                webClient,
                new PublicApiProperties(
                        serviceKey,
                        "https://x",
                        "https://emergency.example",
                        "https://x",
                        "https://x",
                        "https://x",
                        "https://x",
                        "https://x"),
                new DataModeProperties(mode),
                new FixtureLoader(new ObjectMapper()));
    }
}
