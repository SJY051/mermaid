package com.mermaid.facility;

import com.mermaid.common.PublicApiException;
import com.mermaid.common.PublicApiUriBuilder;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import java.io.InputStream;
import java.io.StringReader;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

/** Korean Astronomy and Space Science Institute public-holiday calendar (data.go.kr 15012690). */
@Component
@RequiredArgsConstructor
public class HolidayApiClient {

    private static final String OP_REST_DAYS = "getRestDeInfo";
    private static final int YEAR_PAGE_SIZE = 100;
    private static final int FIXTURE_YEAR = 2026;
    private static final String FIXTURE = "fixtures/holiday_2026.xml";

    private final WebClient publicApiWebClient;
    private final PublicApiProperties properties;
    private final DataModeProperties dataMode;

    /** A calendar is immutable for its year; cache the full date set rather than individual dates. */
    @Cacheable(value = "holidaysByYear.v1", key = "#root.target.cacheKeyFor(#year)", sync = true)
    public HolidayYear holidaysFor(int year) {
        if (dataMode.isFixtureOnly() || !properties.isConfigured()) {
            return fixtureYear(year);
        }

        try {
            return parse(fetch(year));
        } catch (Exception ignored) {
            // A fixture calendar mixed with live facility rows would make a fixture-derived opening
            // decision look live. Fail rather than route around per-fetch provenance.
            // Do not retain the upstream exception: WebClient failures can include the full request
            // URI, whose service-key query parameter must never reach the global exception logger.
            throw new PublicApiException("Public-holiday lookup failed for year " + year);
        }
    }

    /** Fixture dates must never be reused as a live calendar decision after a mode change. */
    public String cacheKeyFor(int year) {
        return year + ":" + dataMode.dataMode().wire();
    }

    URI uriFor(int year) {
        return PublicApiUriBuilder.of(properties.holidayBaseUrl(), OP_REST_DAYS)
                .serviceKey(properties.serviceKey())
                .param("solYear", year)
                .param("pageNo", 1)
                .param("numOfRows", YEAR_PAGE_SIZE)
                .build();
    }

    protected String fetch(int year) {
        return publicApiWebClient.get().uri(uriFor(year)).retrieve().bodyToMono(String.class).block();
    }

    HolidayYear parse(String xml) {
        Document document = secureDocument(xml);
        if (!"00".equals(text(document.getDocumentElement(), "resultCode"))) {
            throw new PublicApiException("Public-holiday API returned a non-success response");
        }

        Set<LocalDate> holidays = new HashSet<>();
        NodeList rows = document.getElementsByTagName("item");
        for (int i = 0; i < rows.getLength(); i++) {
            Node row = rows.item(i);
            if (row.getNodeType() != Node.ELEMENT_NODE) {
                continue;
            }
            Element item = (Element) row;
            if (!"Y".equals(text(item, "isHoliday"))) {
                continue;
            }
            try {
                holidays.add(
                        LocalDate.parse(
                                text(item, "locdate"), DateTimeFormatter.BASIC_ISO_DATE));
            } catch (RuntimeException ignored) {
                // A malformed row cannot prove that any particular date is a holiday.
            }
        }
        return new HolidayYear(holidays);
    }

    private HolidayYear fixtureYear(int year) {
        if (year != FIXTURE_YEAR) {
            throw new PublicApiException("No captured public-holiday fixture for year " + year);
        }
        try (InputStream in = new ClassPathResource(FIXTURE).getInputStream()) {
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (PublicApiException e) {
            throw e;
        } catch (Exception e) {
            throw new PublicApiException("Could not read public-holiday fixture", e);
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
            throw new PublicApiException("Could not parse public-holiday XML", e);
        }
    }

    private static String text(Element element, String name) {
        NodeList nodes = element.getElementsByTagName(name);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }

    public record HolidayYear(Set<LocalDate> dates) {

        public HolidayYear {
            dates = Set.copyOf(dates);
        }

        public boolean isHoliday(LocalDate date) {
            return dates.contains(date);
        }
    }
}
