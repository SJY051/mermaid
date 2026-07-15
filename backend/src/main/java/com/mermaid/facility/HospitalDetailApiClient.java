package com.mermaid.facility;

import com.fasterxml.jackson.databind.JsonNode;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.PublicApiException;
import com.mermaid.common.PublicApiResponse;
import com.mermaid.common.PublicApiUriBuilder;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

/** HIRA hospital-detail adapter for official treatment hours and closures (DEV-203b). */
@Slf4j
@Component
public class HospitalDetailApiClient {

    private static final String OP_BY_YKIHO = "getDtlInfo2.8";
    private static final String FIXTURE = "hospital_detail.json";
    // hospital_detail.json is one captured 강북삼성병원 response. HIRA's detail payload does not
    // echo ykiho, so this mapping is the evidence that lets fixture mode use it truthfully.
    private static final String FIXTURE_YKIHO =
            "JDQ4MTg4MSM1MSMkMSMkMCMkODkkMzgxMzUxIzExIyQxIyQzIyQ3OSQ0NjEwMDIjNjEjJDEjJDQjJDgz";
    private static final Pattern LUNCH_RANGE =
            Pattern.compile("\\s*(\\d{1,2}):(\\d{2})\\s*~\\s*(\\d{1,2}):(\\d{2})\\s*");

    private final WebClient publicApiWebClient;
    private final PublicApiProperties properties;
    private final DataModeProperties dataMode;
    private final FixtureLoader fixtures;
    private final Clock clock;

    @Autowired
    public HospitalDetailApiClient(
            WebClient publicApiWebClient,
            PublicApiProperties properties,
            DataModeProperties dataMode,
            FixtureLoader fixtures,
            Clock clock) {
        this.publicApiWebClient = publicApiWebClient;
        this.properties = properties;
        this.dataMode = dataMode;
        this.fixtures = fixtures;
        this.clock = clock;
    }

    public HospitalDetailApiClient(
            WebClient publicApiWebClient,
            PublicApiProperties properties,
            DataModeProperties dataMode,
            FixtureLoader fixtures) {
        this(publicApiWebClient, properties, dataMode, fixtures, Clock.systemUTC());
    }

    /** Looks up HIRA's treatment-hours detail for a stable hospital identifier. */
    @Cacheable(value = "hospitalDetails", key = "#ykiho")
    public HospitalDetailBatch findByYkiho(String ykiho) {
        if (dataMode.isFixtureOnly()) {
            return fixtureBatch(ykiho);
        }
        if (!properties.isConfigured()) {
            log.warn("DATA_GO_KR_SERVICE_KEY is not set — falling back to hospital detail fixture data");
            return fixtureBatch(ykiho);
        }

        try {
            JsonNode raw =
                    publicApiWebClient
                            .get()
                            .uri(uriFor(ykiho))
                            .retrieve()
                            .bodyToMono(JsonNode.class)
                            .block();
            return new HospitalDetailBatch(parse(raw, ykiho), SourceRef.DataMode.LIVE, Instant.now(clock));
        } catch (Exception ignored) {
            if (dataMode.allowsFallback()) {
                // WebClient failures may include the request URI and its serviceKey. Neither the
                // message nor the cause may cross this provider boundary.
                log.warn("hospital detail lookup failed, falling back to fixture");
                return fixtureBatch(ykiho);
            }
            throw new PublicApiException("Hospital detail lookup failed for " + ykiho);
        }
    }

    private HospitalDetailBatch fixtureBatch(String ykiho) {
        if (!FIXTURE_YKIHO.equals(ykiho)) {
            // A fixture from another hospital cannot establish this hospital's open status. Unknown
            // is the safe result; otherwise open_now would filter on a stranger's timetable.
            return new HospitalDetailBatch(
                    HospitalDetail.empty(ykiho), SourceRef.DataMode.FIXTURE, Instant.now(clock));
        }
        return new HospitalDetailBatch(
                parse(fixtures.load(FIXTURE), ykiho), SourceRef.DataMode.FIXTURE, Instant.now(clock));
    }

    /** HIRA's version suffix belongs on the operation name too; detail also requires `_type=json`. */
    URI uriFor(String ykiho) {
        return PublicApiUriBuilder.of(properties.hospitalDetailBaseUrl(), OP_BY_YKIHO)
                .serviceKey(properties.serviceKey())
                .param("ykiho", ykiho)
                .param("_type", "json")
                .build();
    }

    private HospitalDetail parse(JsonNode raw, String ykiho) {
        List<JsonNode> rows = PublicApiResponse.of(raw).requireOk().items();
        if (rows.isEmpty()) {
            return HospitalDetail.empty(ykiho);
        }

        JsonNode row = rows.getFirst();
        Map<Integer, List<String>> weekdayHours = new LinkedHashMap<>();
        addHours(weekdayHours, 1, row, "trmtMon");
        addHours(weekdayHours, 2, row, "trmtTue");
        addHours(weekdayHours, 3, row, "trmtWed");
        addHours(weekdayHours, 4, row, "trmtThu");
        addHours(weekdayHours, 5, row, "trmtFri");
        addHours(weekdayHours, 6, row, "trmtSat");
        addHours(weekdayHours, 7, row, "trmtSun");

        return new HospitalDetail(
                ykiho,
                Map.copyOf(weekdayHours),
                lunchBreak(PublicApiResponse.text(row, "lunchWeek")),
                isClosed(PublicApiResponse.text(row, "noTrmtSun")),
                isClosed(PublicApiResponse.text(row, "noTrmtHoli")),
                yn(PublicApiResponse.text(row, "emyDayYn")),
                yn(PublicApiResponse.text(row, "emyNgtYn")));
    }

    private static void addHours(
            Map<Integer, List<String>> hours, int day, JsonNode row, String fieldPrefix) {
        String start = PublicApiResponse.text(row, fieldPrefix + "Start");
        String end = PublicApiResponse.text(row, fieldPrefix + "End");
        if (start != null && !start.isBlank() && end != null && !end.isBlank()) {
            hours.put(day, List.of(start, end));
        }
    }

    private static Optional<LunchBreak> lunchBreak(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        Matcher matcher = LUNCH_RANGE.matcher(raw);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        try {
            LocalTime start = LocalTime.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)));
            LocalTime end = LocalTime.of(Integer.parseInt(matcher.group(3)), Integer.parseInt(matcher.group(4)));
            if (!end.isAfter(start)) {
                return Optional.empty();
            }
            return Optional.of(
                    new LunchBreak(start, end));
        } catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static boolean isClosed(String raw) {
        return "휴진".equals(raw) || "Y".equalsIgnoreCase(raw);
    }

    /** HIRA's emergency flags are Y/N; anything else (absent, blank) is genuinely unknown, so null. */
    private static Boolean yn(String raw) {
        if ("Y".equalsIgnoreCase(raw)) {
            return Boolean.TRUE;
        }
        if ("N".equalsIgnoreCase(raw)) {
            return Boolean.FALSE;
        }
        return null;
    }

    /**
     * Official detail values, including a separately modelled lunch closure.
     *
     * @param emergencyDay HIRA {@code emyDayYn}: daytime ER available; {@code null} when unknown
     * @param emergencyNight HIRA {@code emyNgtYn}: night ER available; {@code null} when unknown
     */
    public record HospitalDetail(
            String ykiho,
            Map<Integer, List<String>> weekdayHours,
            Optional<LunchBreak> lunchBreak,
            boolean sundayClosed,
            boolean holidayClosed,
            Boolean emergencyDay,
            Boolean emergencyNight) {

        private static HospitalDetail empty(String ykiho) {
            return new HospitalDetail(ykiho, Map.of(), Optional.empty(), false, false, null, null);
        }
    }

    /** The free-text HIRA lunch interval after strict parsing; malformed values remain unknown. */
    public record LunchBreak(LocalTime start, LocalTime end) {}

    /** Detail data plus this request's origin, required for correct hybrid-fallback provenance. */
    public record HospitalDetailBatch(HospitalDetail detail, SourceRef.DataMode origin, Instant retrievedAt) {
        public HospitalDetailBatch(HospitalDetail detail, SourceRef.DataMode origin) {
            this(detail, origin, Instant.now());
        }
    }
}
