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
     * @throws PublicApiException when the fixture is missing — a broken demo must fail loudly, not
     *     quietly return an empty list that looks like "no pharmacies nearby"
     */
    public JsonNode load(String name) {
        return cache.computeIfAbsent(name, this::read);
    }

    private JsonNode read(String name) {
        String path = "/fixtures/" + name;
        try (InputStream in = FixtureLoader.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new PublicApiException("fixture not found on classpath: " + path);
            }
            log.debug("loaded fixture {}", path);
            return objectMapper.readTree(in);
        } catch (PublicApiException e) {
            throw e;
        } catch (Exception e) {
            throw new PublicApiException("could not read fixture " + path, e);
        }
    }
}
