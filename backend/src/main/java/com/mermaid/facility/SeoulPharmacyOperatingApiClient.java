package com.mermaid.facility;

import com.mermaid.common.PublicApiException;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.SeoulPharmacyOperatingProperties;
import com.mermaid.facility.domain.DutyTable;
import com.mermaid.facility.domain.OpenInterval;
import java.io.StringReader;
import java.net.URI;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Seoul Open Data Plaza {@code TbPharmacyOperateInfo} weekly pharmacy schedules. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SeoulPharmacyOperatingApiClient {

    private static final int PAGE_SIZE = 1000;
    private static final int MAX_PAGES = 10;
    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    private static final DateTimeFormatter WORK_DTTM =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.S");

    private final WebClient publicApiWebClient;
    private final SeoulPharmacyOperatingProperties properties;
    private final DataModeProperties dataMode;

    /**
     * One shared table prevents an unsupported HPID filter from becoming one upstream call per pin.
     * Failures throw rather than caching an empty result, so a short outage cannot hide schedules for
     * the normal six-hour cache TTL.
     */
    @Cacheable(value = "seoulPharmacyOperating.v1", key = "#root.target.cacheKey()", sync = true)
    public OperatingTable operatingTable() {
        if (dataMode.isFixtureOnly() || !properties.isConfigured()) {
            return OperatingTable.empty(SourceRef.DataMode.FIXTURE, Instant.now());
        }

        try {
            Page first = parsePage(fetch(uriFor(1, PAGE_SIZE)));
            int pages = Math.min((int) Math.ceil(first.totalCount() / (double) PAGE_SIZE), MAX_PAGES);
            if (pages == 0 || (int) Math.ceil(first.totalCount() / (double) PAGE_SIZE) > MAX_PAGES) {
                throw new PublicApiException("Seoul pharmacy operating-hours pagination is invalid");
            }

            List<RawOperatingRow> rows = new ArrayList<>(first.rows());
            for (int page = 2; page <= pages; page++) {
                int start = (page - 1) * PAGE_SIZE + 1;
                rows.addAll(parsePage(fetch(uriFor(start, page * PAGE_SIZE))).rows());
            }
            return OperatingTable.index(rows, Instant.now());
        } catch (PublicApiException e) {
            throw e;
        } catch (Exception ignored) {
            // Do not retain or log the provider exception: its URI embeds the API key.
            log.warn("seoul_pharmacy_operating_lookup_failed");
            throw new PublicApiException("Seoul pharmacy operating-hours lookup failed");
        }
    }

    /** Public because Spring evaluates the cache key through its AOP proxy. */
    public String cacheKey() {
        return dataMode.dataMode().wire() + ":" + (properties.isConfigured() ? "live" : "fixture");
    }

    URI uriFor(int start, int end) {
        String configured = properties.seoulPharmacyOperatingUrl().replaceAll("/\\d+/\\d+/?$", "");
        return URI.create(configured + "/" + start + "/" + end);
    }

    protected String fetch(URI uri) {
        return publicApiWebClient.get().uri(uri).retrieve().bodyToMono(String.class).block();
    }

    Page parsePage(String xml) {
        Document document = secureDocument(xml);
        Element root = document.getDocumentElement();
        Element result = first(root, "RESULT");
        if (result == null || !"INFO-000".equals(text(result, "CODE"))) {
            throw new PublicApiException("Seoul pharmacy operating-hours API returned a non-success response");
        }

        int totalCount;
        try {
            totalCount = Integer.parseInt(text(root, "list_total_count"));
        } catch (RuntimeException e) {
            throw new PublicApiException("Seoul pharmacy operating-hours response omitted total count");
        }

        List<RawOperatingRow> rows = new ArrayList<>();
        NodeList nodes = root.getElementsByTagName("row");
        for (int i = 0; i < nodes.getLength(); i++) {
            Node node = nodes.item(i);
            if (node.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element row = (Element) node;
            String hpid = text(row, "HPID");
            if (hpid.isBlank()) {
                continue;
            }
            Map<Integer, List<String>> hours = new HashMap<>();
            Set<Integer> unavailableDays = new HashSet<>();
            for (int day = 1; day <= 8; day++) {
                String start = text(row, "DUTYTIME" + day + "S");
                String end = text(row, "DUTYTIME" + day + "C");
                if (isUsableInterval(start, end)) {
                    hours.put(day, List.of(start, end));
                } else {
                    // A blank, incomplete, or malformed provider value is not evidence that the
                    // pharmacy is closed. Keep that distinction through to isOpenNow.
                    unavailableDays.add(day);
                }
            }
            rows.add(
                    new RawOperatingRow(
                            hpid,
                            new DutyTable(hours, SourceRef.DataMode.LIVE),
                            Set.copyOf(unavailableDays),
                            workUpdatedAt(text(row, "WORK_DTTM"))));
        }
        return new Page(totalCount, rows);
    }

    private static boolean isUsableInterval(String start, String end) {
        boolean bothClosed = isClosedMarker(start) && isClosedMarker(end);
        return bothClosed
                || (OpenInterval.parseHhmm(start) != null && OpenInterval.parseHhmm(end) != null);
    }

    private static boolean isClosedMarker(String value) {
        return "0".equals(value) || "0000".equals(value);
    }

    private static Instant workUpdatedAt(String value) {
        if (value.isBlank()) {
            return null;
        }
        try {
            return LocalDateTime.parse(value, WORK_DTTM).atZone(KST).toInstant();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private static Document secureDocument(String xml) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception e) {
            throw new PublicApiException("Could not parse Seoul pharmacy operating-hours XML");
        }
    }

    private static Element first(Element element, String tag) {
        NodeList nodes = element.getElementsByTagName(tag);
        return nodes.getLength() == 0 ? null : (Element) nodes.item(0);
    }

    private static String text(Element element, String tag) {
        Element child = first(element, tag);
        return child == null ? "" : child.getTextContent().trim();
    }

    record Page(int totalCount, List<RawOperatingRow> rows) {}

    public record RawOperatingRow(
            String hpid,
            DutyTable weeklyHours,
            Set<Integer> unavailableDays,
            Instant scheduleUpdatedAt) {

        public RawOperatingRow {
            unavailableDays = Set.copyOf(unavailableDays);
        }
    }

    public record OperatingTable(
            Map<String, RawOperatingRow> byHpid, SourceRef.DataMode origin, Instant retrievedAt) {

        static OperatingTable empty(SourceRef.DataMode origin, Instant retrievedAt) {
            return new OperatingTable(Map.of(), origin, retrievedAt);
        }

        static OperatingTable index(List<RawOperatingRow> rows, Instant retrievedAt) {
            Map<String, RawOperatingRow> indexed = new HashMap<>();
            Set<String> duplicates = new HashSet<>();
            for (RawOperatingRow row : rows) {
                if (indexed.putIfAbsent(row.hpid(), row) != null) {
                    duplicates.add(row.hpid());
                }
            }
            duplicates.forEach(indexed::remove);
            return new OperatingTable(Map.copyOf(indexed), SourceRef.DataMode.LIVE, retrievedAt);
        }

        public RawOperatingRow forHpid(String hpid) {
            return byHpid.get(hpid);
        }
    }
}
