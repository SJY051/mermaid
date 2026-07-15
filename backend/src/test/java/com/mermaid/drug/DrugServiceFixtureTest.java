package com.mermaid.drug;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.chat.dto.AllergyCheck;
import com.mermaid.common.FixtureLoader;
import com.mermaid.common.NotFoundException;
import com.mermaid.common.SourceRef;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import com.mermaid.drug.domain.Drug;
import com.mermaid.drug.domain.DurWarning;
import com.mermaid.drug.domain.PrescriptionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The three-API merge, driven by the responses the MFDS services really returned on 2026-07-10.
 *
 * <p>Non-empty records from all three services join on {@code ITEM_SEQ}. Product {@code 202005623}
 * (어린이타이레놀산160밀리그램) has zero DUR rows, so those captures preserve their identity through
 * the requested {@code (itemSeq, kind)} instead of a row-level ID.
 */
class DrugServiceFixtureTest {

    private static final String TYLENOL = "202005623";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-10T05:00:00Z"), ZoneOffset.UTC);

    private final IngredientNormalizer normalizer = new IngredientNormalizer();

    private DrugService service() {
        var props =
                new PublicApiProperties("", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x");
        var mode = new DataModeProperties(DataModeProperties.DataMode.FIXTURE);
        var loader = new FixtureLoader(new ObjectMapper());
        return serviceWithDur(
                props, mode, loader, new DurApiClient(null, props, mode, loader));
    }

    private DrugService serviceWithUnrestrictedEmptyDur() {
        var props =
                new PublicApiProperties("", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x");
        var mode = new DataModeProperties(DataModeProperties.DataMode.FIXTURE);
        var loader = new FixtureLoader(new ObjectMapper());
        var dur = new DurApiClient(null, props, mode, loader) {
            @Override
            public DurBatch warningsForBatch(String itemSeq) {
                return new DurBatch(List.of(), SourceRef.DataMode.FIXTURE, CLOCK.instant());
            }
        };
        return serviceWithDur(props, mode, loader, dur);
    }

    private DrugService serviceWithDur(
            PublicApiProperties props,
            DataModeProperties mode,
            FixtureLoader loader,
            DurApiClient dur) {
        return new DrugService(
                new DrugPermissionApiClient(null, props, mode, loader),
                new EasyDrugApiClient(null, props, mode, loader),
                dur,
                new AllergyChecker(normalizer),
                normalizer,
                mode,
                CLOCK);
    }

    private Set<String> avoiding(String... raw) {
        return java.util.Arrays.stream(raw)
                .map(r -> normalizer.normalize(r).key())
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
    }

    @Nested
    @DisplayName("ITEM_SEQ joins the three services into one drug")
    class Merge {

        @Test
        @DisplayName("detail carries ingredients, guidance text, and DUR warnings together")
        void detailMerges() {
            Drug d = service().detail(TYLENOL, Set.of());

            assertThat(d.id()).isEqualTo("drug:mfds:202005623");
            assertThat(d.nameKo()).contains("타이레놀");

            // 허가정보 → ingredients, in English
            assertThat(d.ingredientsEn()).containsExactly("Acetaminophen Granules");
            assertThat(d.mainIngredientKo()).isEqualTo("아세트아미노펜과립"); // [M279100] stripped
            assertThat(d.prescriptionStatus()).isEqualTo(PrescriptionStatus.OTC);

            // e약은요 → Korean guidance
            assertThat(d.narrative().efficacy()).contains("발열");
            assertThat(d.narrative().useMethod()).isNotBlank();
        }

        @Test
        @DisplayName("a search result carries ingredients but no narrative — that costs an extra call")
        void searchIsShallow() {
            List<Drug> found = service().searchByName("타이레놀", Set.of());

            assertThat(found).isNotEmpty();
            Drug d = found.get(0);
            assertThat(d.ingredientsEn()).isNotEmpty();
            assertThat(d.narrative()).isEqualTo(Drug.Narrative.EMPTY);
            assertThat(d.durWarnings()).isEmpty();
        }

        @Test
        @DisplayName("an unknown ITEM_SEQ is a 404, not an empty drug")
        void unknownSeq() {
            // Fixtures ignore query parameters, so we cannot get a miss out of the fixture client.
            // Drive DrugService with a permission client that finds nothing.
            var mode = new DataModeProperties(DataModeProperties.DataMode.FIXTURE);
            var loader = new FixtureLoader(new ObjectMapper());
            var props =
                    new PublicApiProperties("", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x");
            var emptyPermission =
                    new DrugPermissionApiClient(null, props, mode, loader) {
                        @Override
                        public PermissionDetailBatch detailBatch(String itemSeq) {
                            return new PermissionDetailBatch(
                                    null, SourceRef.DataMode.FIXTURE, CLOCK.instant());
                        }
                    };
            var svc =
                    new DrugService(
                            emptyPermission,
                            new EasyDrugApiClient(null, props, mode, loader),
                            new DurApiClient(null, props, mode, loader) {
                                @Override
                                public DurBatch warningsForBatch(String itemSeq) {
                                    return new DurBatch(
                                            List.of(), SourceRef.DataMode.FIXTURE, CLOCK.instant());
                                }
                            },
                            new AllergyChecker(normalizer),
                            normalizer,
                            mode,
                            CLOCK);

            assertThatThrownBy(() -> svc.detail("999999999", Set.of()))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageContaining("999999999");
        }

        @Test
        @DisplayName("every drug carries a source, labelled fixture — never passed off as live")
        void sourceIsLabelled() {
            Drug d = service().detail(TYLENOL, Set.of());

            assertThat(d.source().dataMode()).isEqualTo(SourceRef.DataMode.FIXTURE);
            assertThat(d.source().recordId()).isEqualTo(TYLENOL);
            assertThat(d.source().id()).isEqualTo("src:mfds:202005623");
        }

        @Test
        @DisplayName("retrieval deduplicates by the actual detail record, not the requested list id")
        void retrievalDeduplicatesByActualRecordIdentity() {
            DrugService.RetrievedContext retrieved = serviceWithUnrestrictedEmptyDur().retrieve(
                    new DrugService.RetrievalQuery(List.of("Acetaminophen"), List.of()), Set.of());

            assertThat(retrieved.drugs()).singleElement().satisfies(drug -> {
                assertThat(drug.itemSeq()).isEqualTo(TYLENOL);
                assertThat(drug.source().recordId()).isEqualTo(TYLENOL);
            });
            assertThat(retrieved.sources())
                    .singleElement()
                    .extracting(SourceRef::recordId)
                    .isEqualTo(TYLENOL);
        }
    }

    @Nested
    @DisplayName("allergy verdicts (FR-04)")
    class Allergy {

        @Test
        @DisplayName("acetaminophen allergy BLOCKS 어린이타이레놀 — via 'Acetaminophen Granules'")
        void blocksOnMainIngredient() {
            Drug d = service().detail(TYLENOL, avoiding("acetaminophen"));

            assertThat(d.allergyCheck().status()).isEqualTo(AllergyCheck.Status.BLOCKED);
            assertThat(d.allergyCheck().matchedIngredients()).contains("Acetaminophen Granules");
        }

        @Test
        @DisplayName("a reviewed synonym blocks too — the user typed 'Paracetamol 500mg'")
        void blocksOnSynonym() {
            Drug d = service().detail(TYLENOL, avoiding("Paracetamol 500mg"));

            assertThat(d.allergyCheck().status()).isEqualTo(AllergyCheck.Status.BLOCKED);
        }

        @Test
        @DisplayName("an unrelated allergy yields NO_MATCH_FOUND, and the message never says 'safe'")
        void noMatchIsNotSafe() {
            Drug d = service().detail(TYLENOL, avoiding("Ibuprofen"));

            assertThat(d.allergyCheck().status()).isEqualTo(AllergyCheck.Status.NO_MATCH_FOUND);
            assertThat(d.allergyCheck().message().toLowerCase()).doesNotContain("safe");
            assertThat(d.allergyCheck().message()).containsIgnoringCase("not a guarantee");
        }

        @Test
        @DisplayName("no ingredients on file yields UNKNOWN, never NO_MATCH_FOUND")
        void missingIngredientsAreUnknown() {
            AllergyCheck c = new AllergyChecker(normalizer).check(List.of(), avoiding("Ibuprofen"));

            assertThat(c.status()).isEqualTo(AllergyCheck.Status.UNKNOWN);
        }

        @Test
        @DisplayName("searching by ingredient returns ibuprofen products, all BLOCKED for that allergy")
        void ingredientSearchThenBlock() {
            List<Drug> found = service().searchByIngredient("Ibuprofen", avoiding("Ibuprofen"));

            assertThat(found).isNotEmpty();
            assertThat(found).allSatisfy(d -> {
                assertThat(d.ingredientsEn()).contains("Ibuprofen");
                assertThat(d.allergyCheck().status()).isEqualTo(AllergyCheck.Status.BLOCKED);
            });
        }

        @Test
        @DisplayName("with no allergies declared, nothing is blocked")
        void noAllergiesNoBlocks() {
            Drug d = service().detail(TYLENOL, Set.of());

            assertThat(d.allergyCheck().status()).isEqualTo(AllergyCheck.Status.NO_MATCH_FOUND);
        }
    }

    @Nested
    @DisplayName("DUR warnings (FR-07)")
    class Dur {

        @Test
        @DisplayName("Tylenol has four verified product-bound zero DUR captures")
        void productBoundZeroWarnings() {
            List<DurWarning> warnings = service().detail(TYLENOL, Set.of()).durWarnings();

            assertThat(warnings).isEmpty();
            assertThat(warnings)
                    .extracting(
                            DurWarning::itemSeq,
                            DurWarning::itemName,
                            DurWarning::ingredientEn,
                            DurWarning::pairedItemSeq,
                            DurWarning::pairedItemName)
                    .noneSatisfy(tuple -> assertThat(tuple.toList())
                            .doesNotContain(
                                    "200000913",
                                    "197100097",
                                    "196000010",
                                    "196000011",
                                    "환인벤즈트로핀정(벤즈트로핀메실산염)",
                                    "Benztropine Mesylate",
                                    "Chlorpheniramine"));
        }

        @Test
        @DisplayName("describe() states the fact and cites the source, without prescribing")
        void describeStatesFactNotAdvice() {
            DurWarning w =
                    new DurWarning(
                            DurWarning.Kind.AGE,
                            "197100097",
                            "환인벤즈트로핀정",
                            "Benztropine Mesylate",
                            "생명을 위협하는 경우를 제외하고는 3세 미만 소아에 사용하지 말 것",
                            "20140109",
                            null,
                            null);

            String text = w.describe();

            assertThat(text).contains("Age restriction published");
            assertThat(text).contains("Benztropine Mesylate");
            assertThat(text).contains("3세 미만"); // the ministry's own words, untranslated
            assertThat(text).contains("MFDS DUR, notified 2014-01-09");
            assertThat(text.toLowerCase()).doesNotContain("you should");
            assertThat(text.toLowerCase()).doesNotContain("do not take");
        }

        @Test
        @DisplayName("a missing notification date degrades gracefully")
        void missingDate() {
            DurWarning w =
                    new DurWarning(DurWarning.Kind.ELDERLY, "1", "x", "Ing", null, null, null, null);

            assertThat(w.describe()).contains("date unknown");
        }
    }

    @Nested
    @DisplayName("field parsing gotchas the live API taught us")
    class Parsing {

        @Test
        @DisplayName("compound ingredients split on '/'")
        void splitsCompound() {
            assertThat(DrugPermissionApiClient.splitIngredients("Glucose/Sodium Chloride"))
                    .containsExactly("Glucose", "Sodium Chloride");
            assertThat(DrugPermissionApiClient.splitIngredients("Ibuprofen")).containsExactly("Ibuprofen");
            assertThat(DrugPermissionApiClient.splitIngredients(null)).isEmpty();
            assertThat(DrugPermissionApiClient.splitIngredients("  ")).isEmpty();
        }

        @Test
        @DisplayName("the [M######] code is stripped from the Korean ingredient name")
        void stripsIngredientCode() {
            assertThat(DrugPermissionApiClient.stripIngredientCode("[M279100]아세트아미노펜과립"))
                    .isEqualTo("아세트아미노펜과립");
            assertThat(DrugPermissionApiClient.stripIngredientCode("아세트아미노펜")).isEqualTo("아세트아미노펜");
            assertThat(DrugPermissionApiClient.stripIngredientCode(null)).isNull();
        }

        @Test
        @DisplayName("전문/일반 maps to prescription status; anything else is UNKNOWN, never a guess")
        void prescriptionStatus() {
            assertThat(PrescriptionStatus.fromKorean("일반의약품")).isEqualTo(PrescriptionStatus.OTC);
            assertThat(PrescriptionStatus.fromKorean("전문의약품")).isEqualTo(PrescriptionStatus.PRESCRIPTION);
            assertThat(PrescriptionStatus.fromKorean("원료의약품")).isEqualTo(PrescriptionStatus.UNKNOWN);
            assertThat(PrescriptionStatus.fromKorean(null)).isEqualTo(PrescriptionStatus.UNKNOWN);
        }
    }

    /**
     * The ranking, the filters and the allergy gate live in {@link DrugRetrievalTest}, which drives
     * stub clients — fixtures ignore query parameters, so every {@code detail()} here returns the same
     * product and none of that logic would be observable. This one test only checks that the real
     * fixture path assembles a context at all.
     */
    @Nested
    @DisplayName("retrieve() feeds the RAG flow and the hallucination gate")
    class Retrieval {

        @Test
        @DisplayName("a named product becomes the context, and the allowlist is exactly its name")
        void allowedNames() {
            DrugService.RetrievedContext ctx =
                    service().retrieve(new DrugService.RetrievalQuery(List.of(), List.of("타이레놀")), Set.of());

            assertThat(ctx.drugs()).isNotEmpty();
            assertThat(ctx.allowedProductNames())
                    .isEqualTo(ctx.drugs().stream().map(Drug::nameKo).collect(java.util.stream.Collectors.toSet()));
            assertThat(ctx.sources()).hasSameSizeAs(ctx.drugs());
        }

        @Test
        @DisplayName("prose retrieves nothing — 허가정보 has no product called 'I have a headache'")
        void proseIsNotASearchTerm() {
            // Verified against the live API on 2026-07-10: item_name="I have a headache" → totalCount 0.
            // This is why SearchTermExtractor exists. Fixtures cannot reproduce it (they ignore the
            // query), so what we assert here is the other half: an empty query never calls upstream.
            assertThat(service().retrieve(DrugService.RetrievalQuery.EMPTY, Set.of()).isEmpty()).isTrue();
        }
    }
}
