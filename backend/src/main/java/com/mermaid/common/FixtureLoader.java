package com.mermaid.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Reads the recorded API responses in {@code classpath:/fixtures/}.
 *
 * <p>These are real responses captured on 2026-07-10, not hand-written examples. Parsing them with
 * the same code that parses live data is the point: a fixture that would not survive the real parser
 * teaches nothing.
 *
 * <p>See {@code src/main/resources/fixtures/README.md} for what each file demonstrates.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FixtureLoader {

    private final ObjectMapper objectMapper;
    private final ConcurrentMap<String, JsonNode> cache = new ConcurrentHashMap<>();

    /**
     * @param name file name under {@code /fixtures/}, e.g. {@code "pharmacy.json"}
     * @throws FixtureIntegrityException when the fixture is missing or corrupt — a broken artifact
     *     must fail loudly, not quietly return an empty list or masquerade as a government outage
     */
    public JsonNode load(String name) {
        return cache.computeIfAbsent(name, this::read);
    }

    private JsonNode read(String name) {
        String path = "/fixtures/" + name;
        try (InputStream in = FixtureLoader.class.getResourceAsStream(path)) {
            if (in == null) {
                throw FixtureIntegrityException.missing();
            }
            JsonNode fixture = objectMapper.readTree(in);
            if (fixture == null || fixture.isNull() || fixture.isMissingNode()) {
                throw FixtureIntegrityException.corrupt(null);
            }
            // Every production JSON fixture is a captured successful data.go.kr envelope. Validate
            // that boundary here so a syntactically valid but damaged artifact cannot later become
            // a PublicApiException and masquerade as a live government outage.
            PublicApiResponse response = PublicApiResponse.of(fixture).requireOk();
            if (!response.hasBody()) {
                throw FixtureIntegrityException.corrupt(null);
            }
            log.debug("loaded fixture {}", path);
            return fixture;
        } catch (FixtureIntegrityException e) {
            throw e;
        } catch (Exception e) {
            throw FixtureIntegrityException.corrupt(e);
        }
    }
}
