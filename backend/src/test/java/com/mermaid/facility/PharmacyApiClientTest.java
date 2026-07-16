package com.mermaid.facility;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.PublicApiException;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.HiraPharmacyProperties;
import com.mermaid.config.PublicApiProperties;
import com.mermaid.facility.domain.DutyTable;
import java.net.URI;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/** Tests for DEV-202's HIRA-first pharmacy directory and provider-specific hours. */
class PharmacyApiClientTest {

    @Test
    @DisplayName("normalizes the HIRA radius response into the existing pharmacy row contract")
    void readsHiraPharmacyLocationResponse() {
        List<URI> requests = new CopyOnWriteArrayList<>();
        WebClient webClient =
                WebClient.builder()
                        .exchangeFunction(
                                request -> {
                                    requests.add(request.url());
                                    return Mono.just(
                                            ClientResponse.create(HttpStatus.OK)
                                                    .header(
                                                            HttpHeaders.CONTENT_TYPE,
                                                            MediaType.APPLICATION_XML_VALUE)
                                                    .body(
                                                            """
                                                            <response>
                                                              <header><resultCode>00</resultCode><resultMsg>NORMAL SERVICE.</resultMsg></header>
                                                              <body><items><item>
                                                                <addr>서울특별시 중랑구 신내로 211</addr>
                                                                <distance>1.0246726246840968E03</distance>
                                                                <telno>02-426-4262</telno>
                                                                <XPos>127.09266937331608</XPos>
                                                                <YPos>37.61634971879214</YPos>
                                                                <yadmNm>송이온누리약국</yadmNm>
                                                                <ykiho>HIRA-YKIHO-1</ykiho>
                                                              </item></items><totalCount>1</totalCount></body>
                                                            </response>
                                                            """)
                                                    .build());
                                })
                        .build();
        var props =
                new PublicApiProperties(
                        "configured-key",
                        "https://nmc.example/ErmctInsttInfoInqireService",
                        "https://x",
                        "https://x",
                        "https://x",
                        "https://x",
                        "https://x");
        PharmacyApiClient client =
                new PharmacyApiClient(
                        webClient,
                        props,
                        new HiraPharmacyProperties("https://hira.example/pharmacyInfoService"),
                        new DataModeProperties(DataModeProperties.DataMode.LIVE),
                        new FixtureLoader(new ObjectMapper()));

        PharmacyApiClient.PharmacyBatch batch = client.findNear(37.5663, 126.9779);
        PharmacyApiClient.RawPharmacy pharmacy = batch.pharmacies().getFirst();

        assertThat(batch.provider()).isEqualTo(PharmacyApiClient.PharmacyProvider.HIRA);
        assertThat(batch.origin()).isEqualTo(SourceRef.DataMode.LIVE);
        assertThat(requests).singleElement().satisfies(uri -> {
            assertThat(uri.getHost()).isEqualTo("hira.example");
            assertThat(uri.getPath()).endsWith("/getParmacyBasisList");
            assertThat(uri.getQuery()).contains("ServiceKey=configured-key");
            assertThat(uri.getQuery()).contains("xPos=126.978");
            assertThat(uri.getQuery()).contains("yPos=37.566");
            assertThat(uri.getQuery()).contains("radius=10100");
            assertThat(uri.getQuery()).contains("numOfRows=1000");
        });
        assertThat(pharmacy.hpid()).isEqualTo("HIRA-YKIHO-1");
        assertThat(pharmacy.name()).isEqualTo("송이온누리약국");
        assertThat(pharmacy.longitude()).isEqualTo(127.09266937331608);
        assertThat(pharmacy.latitude()).isEqualTo(37.61634971879214);
        assertThat(pharmacy.distanceKm()).isEqualTo(1.0246726246840968);
    }

    @Test
    @DisplayName("reads every HIRA page because the primary directory is not distance-sorted")
    void readsEveryHiraPage() {
        List<URI> requests = new CopyOnWriteArrayList<>();
        WebClient webClient =
                WebClient.builder()
                        .exchangeFunction(
                                request -> {
                                    requests.add(request.url());
                                    boolean secondPage = request.url().getQuery().contains("pageNo=2");
                                    String ykiho = secondPage ? "page-2-nearest" : "page-1-farther";
                                    String distance = secondPage ? "10" : "900";
                                    return Mono.just(
                                            ClientResponse.create(HttpStatus.OK)
                                                    .header(
                                                            HttpHeaders.CONTENT_TYPE,
                                                            MediaType.APPLICATION_XML_VALUE)
                                                    .body(
                                                            """
                                                            <response>
                                                              <header><resultCode>00</resultCode><resultMsg>NORMAL SERVICE.</resultMsg></header>
                                                              <body><items><item>
                                                                <distance>%s</distance><XPos>126.978</XPos><YPos>37.566</YPos>
                                                                <yadmNm>Pharmacy</yadmNm><ykiho>%s</ykiho>
                                                              </item></items><totalCount>1001</totalCount></body>
                                                            </response>
                                                            """
                                                                    .formatted(distance, ykiho))
                                                    .build());
                                })
                        .build();
        PharmacyApiClient client = liveClient(webClient, DataModeProperties.DataMode.LIVE);

        PharmacyApiClient.PharmacyBatch batch = client.findNear(37.5663, 126.9779);

        assertThat(requests).hasSize(2);
        assertThat(requests)
                .extracting(URI::getQuery)
                .anySatisfy(query -> assertThat(query).contains("pageNo=2"));
        assertThat(batch.pharmacies())
                .extracting(PharmacyApiClient.RawPharmacy::hpid)
                .containsExactly("page-1-farther", "page-2-nearest");
    }

    @Test
    @DisplayName("reconstructs HIRA identity by name but accepts only the requested ykiho")
    void reconstructsHiraIdentityWithoutTrustingNameAlone() {
        List<URI> requests = new CopyOnWriteArrayList<>();
        WebClient webClient =
                WebClient.builder()
                        .exchangeFunction(
                                request -> {
                                    requests.add(request.url());
                                    return Mono.just(
                                            ClientResponse.create(HttpStatus.OK)
                                                    .header(
                                                            HttpHeaders.CONTENT_TYPE,
                                                            MediaType.APPLICATION_XML_VALUE)
                                                    .body(
                                                            """
                                                            <response>
                                                              <header><resultCode>00</resultCode><resultMsg>NORMAL SERVICE.</resultMsg></header>
                                                              <body><items>
                                                                <item><addr>Wrong address</addr><XPos>126.1</XPos><YPos>37.1</YPos>
                                                                  <yadmNm>같은약국</yadmNm><ykiho>other-ykiho</ykiho></item>
                                                                <item><addr>서울 중구</addr><telno>02-000-0000</telno>
                                                                  <XPos>126.978</XPos><YPos>37.566</YPos>
                                                                  <yadmNm>같은약국</yadmNm><ykiho>target-ykiho</ykiho></item>
                                                              </items><totalCount>2</totalCount></body>
                                                            </response>
                                                            """)
                                                    .build());
                                })
                        .build();
        PharmacyApiClient client = liveClient(webClient, DataModeProperties.DataMode.LIVE);

        PharmacyApiClient.HiraIdentityBatch batch =
                client.hiraIdentity("target-ykiho", "같은약국");

        assertThat(requests).singleElement().satisfies(uri -> {
            assertThat(uri.getPath()).endsWith("/getParmacyBasisList");
            assertThat(uri.getQuery()).contains("yadmNm=같은약국");
        });
        assertThat(batch.origin()).isEqualTo(SourceRef.DataMode.LIVE);
        assertThat(batch.pharmacy()).isNotNull();
        assertThat(batch.pharmacy().hpid()).isEqualTo("target-ykiho");
        assertThat(batch.pharmacy().address()).isEqualTo("서울 중구");
    }

    @Test
    @DisplayName("uses the NMC live directory only when the HIRA primary fails")
    void fallsBackFromHiraToNmc() {
        List<URI> requests = new CopyOnWriteArrayList<>();
        WebClient webClient =
                WebClient.builder()
                        .exchangeFunction(
                                request -> {
                                    requests.add(request.url());
                                    if ("hira.example".equals(request.url().getHost())) {
                                        return Mono.just(
                                                ClientResponse.create(
                                                                HttpStatus.SERVICE_UNAVAILABLE)
                                                        .build());
                                    }
                                    return Mono.just(
                                            ClientResponse.create(HttpStatus.OK)
                                                    .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                                                    .body(
                                                            """
                                                            {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":{
                                                              "hpid":"C1110693","dutyName":"청실약국","dutyAddr":"서울 중구",
                                                              "dutyTel1":"02-000-0000","latitude":37.5664,"longitude":126.978,
                                                              "distance":0.14,"startTime":"0900","endTime":"1900"
                                                            }},"totalCount":1}}}
                                                            """)
                                                    .build());
                                })
                        .build();
        PharmacyApiClient client = liveClient(webClient, DataModeProperties.DataMode.LIVE);

        PharmacyApiClient.PharmacyBatch batch = client.findNear(37.5663, 126.9779);

        assertThat(requests).extracting(URI::getHost).containsExactly("hira.example", "nmc.example");
        assertThat(batch.provider()).isEqualTo(PharmacyApiClient.PharmacyProvider.NMC);
        assertThat(batch.origin()).isEqualTo(SourceRef.DataMode.LIVE);
        assertThat(batch.pharmacies())
                .singleElement()
                .extracting(PharmacyApiClient.RawPharmacy::hpid)
                .isEqualTo("C1110693");
    }

    @Test
    @DisplayName("uses the NMC directory for open-now searches without calling HIRA")
    void usesNmcDirectoryForOpenNowWithoutCallingHira() {
        List<URI> requests = new CopyOnWriteArrayList<>();
        WebClient webClient =
                WebClient.builder()
                        .exchangeFunction(
                                request -> {
                                    requests.add(request.url());
                                    if ("hira.example".equals(request.url().getHost())) {
                                        return Mono.just(
                                                ClientResponse.create(HttpStatus.OK)
                                                        .header(
                                                                HttpHeaders.CONTENT_TYPE,
                                                                MediaType.APPLICATION_XML_VALUE)
                                                        .body(
                                                                """
                                                                <response><header><resultCode>00</resultCode></header>
                                                                  <body><items><item><ykiho>HIRA-YKIHO</ykiho>
                                                                    <yadmNm>청실약국</yadmNm><XPos>126.978</XPos><YPos>37.5664</YPos>
                                                                  </item></items><totalCount>1</totalCount></body></response>
                                                                """)
                                                        .build());
                                    }
                                    return Mono.just(
                                            ClientResponse.create(HttpStatus.OK)
                                                    .header(
                                                            HttpHeaders.CONTENT_TYPE,
                                                            MediaType.APPLICATION_JSON_VALUE)
                                                    .body(
                                                            """
                                                            {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":{
                                                              "hpid":"C1110693","dutyName":"청실약국","dutyAddr":"서울 중구",
                                                              "latitude":37.5664,"longitude":126.978,"distance":0.14
                                                            }},"totalCount":1}}}
                                                            """)
                                                    .build());
                                })
                        .build();
        PharmacyApiClient client = liveClient(webClient, DataModeProperties.DataMode.LIVE);

        PharmacyApiClient.PharmacyBatch batch =
                client.findNearForOpenNow(37.5663, 126.9779, 1000);

        assertThat(requests).extracting(URI::getHost).containsExactly("nmc.example");
        assertThat(batch.provider()).isEqualTo(PharmacyApiClient.PharmacyProvider.NMC);
        assertThat(batch.pharmacies())
                .singleElement()
                .extracting(PharmacyApiClient.RawPharmacy::hpid)
                .isEqualTo("C1110693");
    }

    @Test
    @DisplayName("rejects far NMC coordinates even when the upstream distance claims they are near")
    void rejectsOutOfRadiusNmcFallbackRows() {
        WebClient webClient =
                WebClient.builder()
                        .exchangeFunction(
                                request -> {
                                    if ("hira.example".equals(request.url().getHost())) {
                                        return Mono.just(
                                                ClientResponse.create(
                                                                HttpStatus.SERVICE_UNAVAILABLE)
                                                        .build());
                                    }
                                    return Mono.just(
                                            ClientResponse.create(HttpStatus.OK)
                                                    .header(
                                                            HttpHeaders.CONTENT_TYPE,
                                                            MediaType.APPLICATION_JSON_VALUE)
                                                    .body(
                                                            """
                                                            {"response":{"header":{"resultCode":"00"},"body":{"items":{"item":{
                                                              "hpid":"far-away","dutyName":"Far pharmacy","dutyAddr":"Seoul",
                                                              "latitude":37.50,"longitude":127.05,"distance":0.01
                                                            }},"totalCount":1}}}
                                                            """)
                                                    .build());
                                })
                        .build();
        PharmacyApiClient client = liveClient(webClient, DataModeProperties.DataMode.HYBRID);

        PharmacyApiClient.PharmacyBatch batch = client.findNear(37.5663, 126.9779, 1000);

        assertThat(batch.origin()).isEqualTo(SourceRef.DataMode.FIXTURE);
        assertThat(batch.provider()).isEqualTo(PharmacyApiClient.PharmacyProvider.NMC);
    }

    @Test
    @DisplayName("keeps a genuinely empty NMC fallback live instead of fabricating fixture rows")
    void keepsEmptyNmcFallbackLive() {
        WebClient webClient =
                WebClient.builder()
                        .exchangeFunction(
                                request -> {
                                    if ("hira.example".equals(request.url().getHost())) {
                                        return Mono.just(
                                                ClientResponse.create(
                                                                HttpStatus.SERVICE_UNAVAILABLE)
                                                        .build());
                                    }
                                    return Mono.just(
                                            ClientResponse.create(HttpStatus.OK)
                                                    .header(
                                                            HttpHeaders.CONTENT_TYPE,
                                                            MediaType.APPLICATION_JSON_VALUE)
                                                    .body(
                                                            """
                                                            {"response":{"header":{"resultCode":"00"},
                                                              "body":{"items":{},"totalCount":0}}}
                                                            """)
                                                    .build());
                                })
                        .build();
        PharmacyApiClient client = liveClient(webClient, DataModeProperties.DataMode.HYBRID);

        PharmacyApiClient.PharmacyBatch batch = client.findNear(37.5663, 126.9779, 1000);

        assertThat(batch.pharmacies()).isEmpty();
        assertThat(batch.origin()).isEqualTo(SourceRef.DataMode.LIVE);
        assertThat(batch.provider()).isEqualTo(PharmacyApiClient.PharmacyProvider.NMC);
    }

    @Test
    @DisplayName("uses an honestly labelled fixture only after both live providers fail in hybrid mode")
    void fallsBackToFixtureAfterBothLiveProvidersFail() {
        WebClient webClient =
                WebClient.builder()
                        .exchangeFunction(
                                request ->
                                        Mono.just(
                                                ClientResponse.create(HttpStatus.SERVICE_UNAVAILABLE).build()))
                        .build();
        PharmacyApiClient client = liveClient(webClient, DataModeProperties.DataMode.HYBRID);

        PharmacyApiClient.PharmacyBatch batch = client.findNear(37.5663, 126.9779);

        assertThat(batch.provider()).isEqualTo(PharmacyApiClient.PharmacyProvider.NMC);
        assertThat(batch.origin()).isEqualTo(SourceRef.DataMode.FIXTURE);
        assertThat(batch.pharmacies()).isNotEmpty();
    }

    @Test
    @DisplayName("provider failures never log or expose a URI-bearing service key")
    void providerFailuresDropSecretBearingMessagesAndCauses() {
        String secret = "never-log-this-service-key";
        WebClient webClient =
                WebClient.builder()
                        .exchangeFunction(
                                request ->
                                        Mono.error(
                                                new IllegalStateException(
                                                        request.url() + " upstream failed " + secret)))
                        .build();
        PharmacyApiClient client = liveClient(webClient, DataModeProperties.DataMode.LIVE);
        Logger logger = (Logger) LoggerFactory.getLogger(PharmacyApiClient.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        try {
            assertThatThrownBy(() -> client.findNear(37.5663, 126.9779, 1000))
                    .isInstanceOf(PublicApiException.class)
                    .hasMessage("Both pharmacy directory providers failed")
                    .hasNoCause()
                    .satisfies(error -> assertThat(error.getSuppressed()).isEmpty());
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .allSatisfy(message -> assertThat(message).doesNotContain(secret, "ServiceKey"));
            appender.list.clear();

            assertThatThrownBy(() -> client.findNearForOpenNow(37.5663, 126.9779, 1000))
                    .isInstanceOf(PublicApiException.class)
                    .hasMessage("NMC open-now pharmacy directory failed")
                    .hasNoCause()
                    .satisfies(error -> assertThat(error.getSuppressed()).isEmpty());
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .allSatisfy(message -> assertThat(message).doesNotContain(secret, "serviceKey"));
        } finally {
            logger.detachAppender(appender);
        }
    }

    private PharmacyApiClient liveClient(
            WebClient webClient, DataModeProperties.DataMode mode) {
        var props =
                new PublicApiProperties(
                        "configured-key",
                        "https://nmc.example/ErmctInsttInfoInqireService",
                        "https://x",
                        "https://x",
                        "https://x",
                        "https://x",
                        "https://x");
        return new PharmacyApiClient(
                webClient,
                props,
                new HiraPharmacyProperties("https://hira.example/pharmacyInfoService"),
                new DataModeProperties(mode),
                new FixtureLoader(new ObjectMapper()));
    }

    private PharmacyApiClient fixtureClient() {
        var props =
                new PublicApiProperties(
                        "",
                        "https://x",
                        "https://x",
                        "https://x",
                        "https://x",
                        "https://x",
                        "https://x",
                        "https://x");
        return new PharmacyApiClient(
                null,
                props,
                new DataModeProperties(DataModeProperties.DataMode.FIXTURE),
                new FixtureLoader(new ObjectMapper()));
    }

    @Test
    @DisplayName("reads mixed string and numeric duty-time fields for the requested HPID")
    void readsOfficialWeeklyHoursFromFixture() {
        DutyTable table = fixtureClient().weeklyHours("C1110693");

        assertThat(table.byDay().get(1)).containsExactly("0900", "1900");
        assertThat(table.byDay().get(6)).containsExactly("1000", "1400");
        assertThat(table.byDay()).doesNotContainKey(7);
        assertThat(table.byDay()).doesNotContainKey(8);
        assertThat(table.origin()).isEqualTo(SourceRef.DataMode.FIXTURE);
    }

    @Test
    @DisplayName("a fixture-served batch is tagged FIXTURE, so the card never claims live")
    void fixtureBatchCarriesFixtureOrigin() {
        var batch = fixtureClient().findNear(37.5663, 126.9779);

        assertThat(batch.pharmacies()).isNotEmpty();
        assertThat(batch.origin()).isEqualTo(SourceRef.DataMode.FIXTURE);
        assertThat(batch.provider()).isEqualTo(PharmacyApiClient.PharmacyProvider.NMC);
    }

    @Test
    @DisplayName("does not give another pharmacy the fixture timetable")
    void ignoresFixtureForDifferentHpid() {
        assertThat(fixtureClient().weeklyHours("C1107705").byDay()).isEmpty();
    }

    @Test
    @DisplayName("requests getParmacyBassInfoInqire with HPID and _type=json")
    void requestsOfficialTimetableEndpoint() {
        URI uri = fixtureClient().weeklyHoursUriFor("C1110693");

        assertThat(uri.getPath()).endsWith("/getParmacyBassInfoInqire");
        assertThat(uri.getQuery()).contains("HPID=C1110693");
        assertThat(uri.getQuery()).contains("_type=json");
    }

    @Test
    @DisplayName("basisDetail reads identity, coordinates and the timetable for the requested HPID")
    void basisDetailReadsFullRecord() {
        var batch = fixtureClient().basisDetail("C1110693");

        assertThat(batch.origin()).isEqualTo(SourceRef.DataMode.FIXTURE);
        var detail = batch.detail();
        assertThat(detail).isNotNull();
        assertThat(detail.name()).isEqualTo("청실약국");
        assertThat(detail.address()).contains("무교로");
        assertThat(detail.phone()).isEqualTo("02-3789-6953");
        // wgs84Lat/wgs84Lon on the basis endpoint, not the location endpoint's latitude/longitude.
        assertThat(detail.latitude()).isEqualTo(37.5672818668855);
        assertThat(detail.longitude()).isEqualTo(126.978921749794);
        assertThat(detail.weeklyHours().byDay().get(1)).containsExactly("0900", "1900");
        assertThat(detail.weeklyHours().byDay().get(6)).containsExactly("1000", "1400");
    }

    @Test
    @DisplayName("basisDetail returns a null detail for an HPID the fixture does not carry")
    void basisDetailMissingHpidIsNull() {
        var batch = fixtureClient().basisDetail("C1107705");

        assertThat(batch.detail()).isNull();
        assertThat(batch.origin()).isEqualTo(SourceRef.DataMode.FIXTURE);
    }

    @Test
    @DisplayName("a matching row without a name or coordinates is rejected, not a partial 200 card")
    void basisDetailRejectsIncompleteRow() throws Exception {
        // The row carries the requested hpid but no dutyName and no wgs84Lat/Lon. Returning it would
        // build a verified-looking Facility with null identity/coordinates — reject it as not found.
        String envelope =
                """
                {"response":{"header":{"resultCode":"00","resultMsg":"OK"},
                 "body":{"items":{"item":{"hpid":"C1110693","dutyAddr":"서울 중구","dutyTel1":"02-000-0000"}}}}}
                """;
        JsonNode raw = new ObjectMapper().readTree(envelope);

        assertThat(fixtureClient().parseBasisDetail(raw, "C1110693", SourceRef.DataMode.LIVE)).isNull();

        // Name and coordinates present, but no address: still rejected (address is a required field).
        String noAddress =
                """
                {"response":{"header":{"resultCode":"00","resultMsg":"OK"},
                 "body":{"items":{"item":{"hpid":"C1110693","dutyName":"청실약국","dutyTel1":"02-000-0000",
                   "wgs84Lat":37.56,"wgs84Lon":126.97}}}}}
                """;
        assertThat(
                        fixtureClient()
                                .parseBasisDetail(
                                        new ObjectMapper().readTree(noAddress),
                                        "C1110693",
                                        SourceRef.DataMode.LIVE))
                .isNull();

        // A whitespace-only address is equally unusable for a person trying to find the pharmacy.
        String blankAddress =
                """
                {"response":{"header":{"resultCode":"00","resultMsg":"OK"},
                 "body":{"items":{"item":{"hpid":"C1110693","dutyName":"청실약국","dutyAddr":"  ",
                   "dutyTel1":"02-000-0000","wgs84Lat":37.56,"wgs84Lon":126.97}}}}}
                """;
        assertThat(
                        fixtureClient()
                                .parseBasisDetail(
                                        new ObjectMapper().readTree(blankAddress),
                                        "C1110693",
                                        SourceRef.DataMode.LIVE))
                .isNull();
    }

    @Test
    @DisplayName("hybrid: a live outage for an hpid the fixture lacks fails unavailable, not a cached 404")
    void hybridDetailOutageForUnknownHpidIsUnavailable() {
        // pharmacyBaseUrl is a closed port, so the live call fails; HYBRID would normally fall back to
        // the fixture. The fixture holds only C1110693, so an outage for any other well-formed hpid
        // must surface as unavailable (retryable, not cached) rather than a 404 that outlives it.
        var props =
                new PublicApiProperties(
                        "decoding-key", "http://127.0.0.1:1", "https://x", "https://x", "https://x",
                        "https://x", "https://x");
        var client =
                new PharmacyApiClient(
                        WebClient.create(),
                        props,
                        new DataModeProperties(DataModeProperties.DataMode.HYBRID),
                        new FixtureLoader(new ObjectMapper()));

        assertThatThrownBy(() -> client.basisDetail("C9999999")).isInstanceOf(PublicApiException.class);

        // The one pharmacy the fixture does hold still falls back to fixture data.
        var batch = client.basisDetail("C1110693");
        assertThat(batch.detail()).isNotNull();
        assertThat(batch.origin()).isEqualTo(SourceRef.DataMode.FIXTURE);
    }

    @Test
    @DisplayName("a live detail failure drops the URI-bearing cause before it can be logged (§2-7)")
    void basisDetailLiveFailureHasNoCause() {
        // pharmacyBaseUrl points at a closed port; in LIVE mode there is no fixture fallback, so the
        // connection error becomes a PublicApiException. Re-adding the WebClient cause (which carries
        // the request URI and its serviceKey) turns hasNoCause() red.
        var props =
                new PublicApiProperties(
                        "decoding-key", "http://127.0.0.1:1", "https://x", "https://x", "https://x",
                        "https://x", "https://x");
        var client =
                new PharmacyApiClient(
                        WebClient.create(),
                        props,
                        new DataModeProperties(DataModeProperties.DataMode.LIVE),
                        new FixtureLoader(new ObjectMapper()));

        assertThatThrownBy(() -> client.basisDetail("C1110693"))
                .isInstanceOf(PublicApiException.class)
                .hasMessage("Pharmacy detail lookup failed for C1110693")
                .hasNoCause();
    }

    // ----- issue #95 -----

    private static final String SECRET_SENTINEL = "decoding-key-DO-NOT-LOG";

    /** A client whose NMC basis fetch fails with an exception carrying the service key and URI. */
    private PharmacyApiClient leakingBasisClient(DataModeProperties.DataMode mode) {
        var props =
                new PublicApiProperties(
                        SECRET_SENTINEL, "https://pharmacy.example", "https://x", "https://x",
                        "https://x", "https://x", "https://x");
        return new PharmacyApiClient(
                null, props, new DataModeProperties(mode), new FixtureLoader(new ObjectMapper())) {
            @Override
            protected JsonNode fetchBasis(String hpid) {
                throw new IllegalStateException(
                        "GET https://pharmacy.example/getParmacyBassInfoInqire?serviceKey="
                                + SECRET_SENTINEL
                                + "&HPID="
                                + hpid);
            }
        };
    }

    @Test
    @DisplayName("a row with a non-finite or out-of-range coordinate is rejected (issue #95)")
    void basisDetailRejectsInvalidCoordinates() throws Exception {
        for (String badCoords :
                List.of(
                        "\"wgs84Lat\":\"NaN\",\"wgs84Lon\":126.97",
                        "\"wgs84Lat\":37.56,\"wgs84Lon\":\"Infinity\"",
                        "\"wgs84Lat\":999,\"wgs84Lon\":126.97")) {
            String envelope =
                    ("{\"response\":{\"header\":{\"resultCode\":\"00\",\"resultMsg\":\"OK\"},"
                                    + "\"body\":{\"items\":{\"item\":{\"hpid\":\"C1110693\","
                                    + "\"dutyName\":\"청실약국\",\"dutyAddr\":\"서울 중구\",%s}}}}}")
                            .formatted(badCoords);
            assertThat(
                            fixtureClient()
                                    .parseBasisDetail(
                                            new ObjectMapper().readTree(envelope),
                                            "C1110693",
                                            SourceRef.DataMode.LIVE))
                    .as(badCoords)
                    .isNull();
        }
    }

    @Test
    @DisplayName("NMC basis failure paths log no service key, URI, or exception text (§2-7, issue #95)")
    void basisFailurePathsLogNoSecrets() {
        Logger logger = (Logger) LoggerFactory.getLogger(PharmacyApiClient.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            var hybrid = leakingBasisClient(DataModeProperties.DataMode.HYBRID);
            // weeklyHours falls back to the fixture and logs; basisDetail for an absent id throws.
            hybrid.weeklyHours("C9999999");
            assertThatThrownBy(() -> hybrid.basisDetail("C9999999"))
                    .isInstanceOf(PublicApiException.class);

            assertThat(logs.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .noneMatch(m -> m.contains(SECRET_SENTINEL))
                    .noneMatch(m -> m.contains("HPID="))
                    .noneMatch(m -> m.contains("pharmacy.example"));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    @DisplayName("a live weekly-hours failure throws without the URI-bearing cause (§2-7, issue #95)")
    void weeklyHoursLiveFailureHasNoCause() {
        assertThatThrownBy(
                        () ->
                                leakingBasisClient(DataModeProperties.DataMode.LIVE)
                                        .weeklyHours("C1110693"))
                .isInstanceOf(PublicApiException.class)
                .hasMessage("Pharmacy weekly-hours lookup failed for C1110693")
                .hasNoCause();
    }
}
