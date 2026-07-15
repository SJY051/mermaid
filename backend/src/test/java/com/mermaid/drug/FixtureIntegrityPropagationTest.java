package com.mermaid.drug;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.common.FixtureIntegrityException;
import com.mermaid.common.FixtureLoader;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import com.mermaid.drug.domain.DurWarning;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

class FixtureIntegrityPropagationTest {

    @Test
    @DisplayName("drug context assembly does not turn a broken detail fixture into an empty result")
    void contextAssemblyPreservesFixtureIntegrity() {
        FixtureLoader loader = missingFixture("permission_detail.json");
        PublicApiProperties properties = properties("");
        DataModeProperties mode =
                new DataModeProperties(DataModeProperties.DataMode.FIXTURE);
        IngredientNormalizer normalizer = new IngredientNormalizer();
        DrugService service =
                new DrugService(
                        new DrugPermissionApiClient(null, properties, mode, loader),
                        new EasyDrugApiClient(null, properties, mode, loader),
                        new DurApiClient(null, properties, mode, loader),
                        new AllergyChecker(normalizer),
                        normalizer,
                        mode,
                        Clock.systemUTC());

        assertThatThrownBy(
                        () ->
                                service.retrieve(
                                        new DrugService.RetrievalQuery(
                                                List.of(), List.of("타이레놀")),
                                        Set.of()))
                .isInstanceOf(FixtureIntegrityException.class);
    }

    @Test
    @DisplayName("the EasyDrug fixture adapter preserves a local integrity failure")
    void easyDrugAdapterPreservesFixtureIntegrity() {
        EasyDrugApiClient client =
                new EasyDrugApiClient(
                        null,
                        properties(""),
                        new DataModeProperties(DataModeProperties.DataMode.FIXTURE),
                        missingFixture("easydrug.json"));

        assertThatThrownBy(() -> client.findBySeq("200008804"))
                .isInstanceOf(FixtureIntegrityException.class);
    }

    @Test
    @DisplayName("the hybrid DUR fallback preserves a local fixture integrity failure")
    void durHybridFallbackPreservesFixtureIntegrity() {
        WebClient failingLiveClient =
                WebClient.builder()
                        .exchangeFunction(request -> Mono.error(new RuntimeException("upstream unavailable")))
                        .build();
        DurApiClient client =
                new DurApiClient(
                        failingLiveClient,
                        properties("configured-test-key"),
                        new DataModeProperties(DataModeProperties.DataMode.HYBRID),
                        missingFixture("dur_usjnt.json"));

        assertThatThrownBy(
                        () -> client.byKind("200000913", DurWarning.Kind.COMBINATION))
                .isInstanceOf(FixtureIntegrityException.class);
    }

    private static FixtureLoader missingFixture(String target) {
        return new FixtureLoader(new ObjectMapper()) {
            @Override
            public JsonNode load(String name) {
                return target.equals(name) ? super.load("missing-fixture.json") : super.load(name);
            }
        };
    }

    private static PublicApiProperties properties(String serviceKey) {
        return new PublicApiProperties(
                serviceKey,
                "https://example.invalid",
                "https://example.invalid",
                "https://example.invalid",
                "https://example.invalid",
                "https://example.invalid",
                "https://example.invalid",
                "https://example.invalid");
    }
}
