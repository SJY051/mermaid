package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.common.SourceRef;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Fixture-level coverage for the boundary between answer binding and post-processing validation.
 *
 * <p>The provider schema constrains model generation, but this project has no standalone JSON Schema
 * engine. Fixtures keep the model-facing empty {@code sourceRefs} array, then this test adds the
 * server-owned {@code SourceRef} objects that {@link AnswerValidator} receives. Schema-invalid
 * fixtures use the contract's DTO-binding fallback and target shapes Jackson reliably rejects.
 */
class AnswerContractTest {

    private final ObjectMapper objectMapper =
            new ObjectMapper()
                    .registerModule(new JavaTimeModule())
                    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final AnswerValidator validator = new AnswerValidator();
    private final JsonNode providerSchema = new AnswerSchemaProvider(objectMapper).get();

    @Nested
    @DisplayName("valid grounded answers")
    class ValidAnswers {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.mermaid.chat.AnswerContractTest#validFixtures")
        @DisplayName("provider binding, grounding, DTO round trip, and all seven invariants pass")
        void validFixturePasses(Path fixture) throws IOException {
            MermAidAnswer grounded = withServerSources(fixture, readProviderAnswer(fixture));
            MermAidAnswer roundTripped =
                    objectMapper.readValue(
                            objectMapper.writeValueAsBytes(grounded), MermAidAnswer.class);

            assertThat(roundTripped).isEqualTo(grounded);
            assertThat(validator.validate(roundTripped, drugNames(roundTripped)))
                    .as("violations for %s", fixture.getFileName())
                    .isEmpty();
        }
    }

    @Nested
    @DisplayName("post-processing invariant fixtures")
    class InvalidAnswers {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.mermaid.chat.AnswerContractTest#invalidFixtures")
        @DisplayName("Invariants 1-7 each reject their isolated violation")
        void invalidFixtureEmitsExpectedViolation(Path fixture) throws IOException {
            MermAidAnswer answer = withServerSources(fixture, readProviderAnswer(fixture));
            List<String> violations =
                    validator.validate(answer, retrievedNamesForInvalidFixture(fixture, answer));

            assertThat(violations)
                    .as("violations for %s", fixture.getFileName())
                    .hasSize(1)
                    .allMatch(v -> v.contains(expectedViolationFragment(fixture)));
        }
    }

    @Nested
    @DisplayName("provider-schema fixtures")
    class SchemaInvalidAnswers {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.mermaid.chat.AnswerContractTest#schemaInvalidFixtures")
        @DisplayName("schema-invalid shapes fail the production DTO-binding fallback")
        void schemaInvalidFixtureFailsBinding(Path fixture) {
            assertProviderSchemaDeclaresConstraint(fixture);

            // AnswerSchemaProvider sends strict JSON Schema to the provider; it is not a local
            // validator. Direct binding is the production fallback available without a new library.
            assertThatThrownBy(() -> read(fixture)).isInstanceOf(JsonProcessingException.class);
        }
    }

    @Nested
    @DisplayName("documented validator gaps")
    class DocumentedValidatorGaps {

        @Test
        @Disabled("DEV-601: validator gap, see report")
        @DisplayName("Invariant 6 - retrieved product ingredients must match the normalized record")
        void invariant6RejectsFabricatedIngredient() throws IOException {
            Path fixture = fixture("invalid/gaps/inv6-mismatched-ingredient.json");
            MermAidAnswer answer = withServerSources(fixture, readProviderAnswer(fixture));

            assertThat(validator.validate(answer, drugNames(answer))).isNotEmpty();
        }

        @Test
        @Disabled("DEV-601: validator gap, see report")
        @DisplayName("Invariant 7 - a URL in the urgency message is rejected")
        void invariant7RejectsUrlInUrgencyMessage() throws IOException {
            Path fixture = fixture("invalid/gaps/inv7-url-in-urgency-message.json");
            MermAidAnswer answer = withServerSources(fixture, readProviderAnswer(fixture));

            assertThat(validator.validate(answer, drugNames(answer)))
                    .anyMatch(v -> v.contains("URL or markup"));
        }

        @Test
        @Disabled("DEV-601: validator gap, see report")
        @DisplayName("Invariant 7 - generic HTML in a scanned field is rejected")
        void invariant7RejectsGenericHtml() throws IOException {
            Path fixture = fixture("invalid/gaps/inv7-generic-html-in-summary.json");
            MermAidAnswer answer = withServerSources(fixture, readProviderAnswer(fixture));

            assertThat(validator.validate(answer, drugNames(answer)))
                    .anyMatch(v -> v.contains("URL or markup"));
        }
    }

    private MermAidAnswer read(Path fixture) throws IOException {
        return objectMapper.readValue(fixture.toFile(), MermAidAnswer.class);
    }

    private MermAidAnswer readProviderAnswer(Path fixture) throws IOException {
        JsonNode payload = objectMapper.readTree(fixture.toFile());
        providerSchema.path("required").forEach(field ->
                assertThat(payload.has(field.asText()))
                        .as("%s has required field %s", fixture.getFileName(), field.asText())
                        .isTrue());
        payload.fieldNames().forEachRemaining(
                field ->
                        assertThat(providerSchema.path("properties").has(field))
                                .as("%s allows top-level field %s", fixture.getFileName(), field)
                                .isTrue());
        assertThat(payload.path("sourceRefs"))
                .as("the model leaves provenance empty for server grounding")
                .isEmpty();
        assertThat(payload.path("schemaVersion"))
                .isEqualTo(providerSchema.at("/properties/schemaVersion/const"));
        assertThat(payload.path("language"))
                .isEqualTo(providerSchema.at("/properties/language/const"));
        return objectMapper.treeToValue(payload, MermAidAnswer.class);
    }

    /**
     * Injects server provenance but deliberately preserves {@code dataStatus}. Invariant 3 exists to
     * reject a bad server label, so its fixture must be able to represent that inconsistent state.
     */
    private static MermAidAnswer withServerSources(Path fixture, MermAidAnswer answer) {
        return new MermAidAnswer(
                answer.schemaVersion(),
                answer.answerId(),
                answer.language(),
                answer.dataStatus(),
                answer.urgency(),
                answer.summary(),
                answer.clarifyingQuestions(),
                answer.guidance(),
                answer.drugs(),
                answer.uiActions(),
                sourcesFor(fixture),
                answer.warnings(),
                answer.disclaimer());
    }

    private static List<SourceRef> sourcesFor(Path fixture) {
        String name = fixture.getFileName().toString();
        return switch (name) {
            case "routine-drugs-sources.json", "inv2-blocked-no-ingredient.json",
                    "inv6-mismatched-ingredient.json" ->
                    List.of(source("src:mfds:tylenol-500", SourceRef.DataMode.LIVE));
            case "no-match-allergy-answer.json" ->
                    List.of(source("src:mfds:setopen-325", SourceRef.DataMode.LIVE));
            case "fixture-data-labelled.json" ->
                    List.of(source("src:mfds:children-tylenol", SourceRef.DataMode.FIXTURE));
            case "multi-drug-answer.json" ->
                    List.of(
                            source("src:mfds:tylenol-500", SourceRef.DataMode.LIVE),
                            source("src:mfds:brufen-200", SourceRef.DataMode.LIVE));
            case "inv1-unknown-sourceref.json" ->
                    List.of(source("src:mfds:known", SourceRef.DataMode.LIVE));
            case "inv3-live-on-fixture.json" ->
                    List.of(source("src:mfds:fixture", SourceRef.DataMode.FIXTURE));
            case "inv6-unretrieved-drug.json" ->
                    List.of(source("src:mfds:invented", SourceRef.DataMode.LIVE));
            default -> List.of();
        };
    }

    private static SourceRef source(String id, SourceRef.DataMode mode) {
        return new SourceRef(
                id,
                "mfds",
                "contract-record",
                Instant.parse("2026-07-10T02:00:00Z"),
                mode,
                "Contract fixture source");
    }

    private void assertProviderSchemaDeclaresConstraint(Path fixture) {
        String name = fixture.getFileName().toString();
        switch (name) {
            case "missing-required-field.json" ->
                    assertThat(providerSchema.at("/$defs/uiAction/required").toString())
                            .contains("\"type\"");
            case "wrong-enum.json" ->
                    assertThat(providerSchema.at("/$defs/uiAction/properties/type/enum").toString())
                            .contains("SHOW_EMERGENCY_CALL")
                            .doesNotContain("NAVIGATE_TO_URL");
            case "wrong-type.json" ->
                    assertThat(providerSchema.at("/properties/drugs/type").asText()).isEqualTo("array");
            default -> throw new IllegalArgumentException("No schema expectation for " + name);
        }
    }

    private static Set<String> retrievedNamesForInvalidFixture(Path fixture, MermAidAnswer answer) {
        if (fixture.getFileName().toString().startsWith("inv6-")) {
            return Set.of();
        }
        return drugNames(answer);
    }

    private static Set<String> drugNames(MermAidAnswer answer) {
        if (answer.drugs() == null) {
            return Set.of();
        }
        return answer.drugs().stream()
                .map(MermAidAnswer.DrugCard::productNameKo)
                .collect(Collectors.toUnmodifiableSet());
    }

    private static String expectedViolationFragment(Path fixture) {
        String name = fixture.getFileName().toString();
        if (name.startsWith("inv1-")) {
            return "unknown source_ref_id";
        }
        if (name.startsWith("inv2-")) {
            return "blocked but names no ingredient";
        }
        if (name.startsWith("inv3-")) {
            return "data_mode=fixture";
        }
        if (name.startsWith("inv4-")) {
            return "no SHOW_EMERGENCY_CALL";
        }
        if (name.startsWith("inv5-")) {
            return "official_data with no source";
        }
        if (name.startsWith("inv6-")) {
            return "never retrieved";
        }
        if (name.startsWith("inv7-")) {
            return "URL or markup";
        }
        throw new IllegalArgumentException("Fixture does not name an invariant: " + name);
    }

    private static Stream<Named<Path>> validFixtures() {
        return listJson("valid", path -> path.getFileName().toString());
    }

    private static Stream<Named<Path>> invalidFixtures() {
        return listJson(
                "invalid",
                path -> {
                    String name = path.getFileName().toString();
                    int separator = name.indexOf('-');
                    String invariant = separator > 3 ? name.substring(3, separator) : "?";
                    return "Invariant " + invariant + " - " + name;
                });
    }

    private static Stream<Named<Path>> schemaInvalidFixtures() {
        return listJson(
                "invalid/schema", path -> "Schema constraint - " + path.getFileName().toString());
    }

    private static Stream<Named<Path>> listJson(
            String directory, java.util.function.Function<Path, String> displayName) {
        try (Stream<Path> paths = Files.list(fixture(directory))) {
            return paths.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted()
                    .map(path -> Named.of(displayName.apply(path), path))
                    .toList()
                    .stream();
        } catch (IOException e) {
            throw new IllegalStateException("Could not list contract fixtures in " + directory, e);
        }
    }

    private static Path fixture(String relativePath) {
        var resource = AnswerContractTest.class.getResource("/contract/" + relativePath);
        if (resource == null) {
            throw new IllegalArgumentException("Missing contract fixture: " + relativePath);
        }
        try {
            return Path.of(resource.toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("Invalid contract fixture URI: " + resource, e);
        }
    }
}
