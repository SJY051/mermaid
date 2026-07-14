package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.chat.DrugContextRetriever.DrugContext;
import com.mermaid.chat.dto.AllergyCheck;
import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.common.SourceRef;
import com.mermaid.drug.DrugService;
import com.mermaid.drug.DrugService.RetrievalQuery;
import com.mermaid.drug.DrugService.RetrievedContext;
import com.mermaid.drug.IngredientNormalizer;
import com.mermaid.drug.domain.Drug;
import com.mermaid.drug.domain.DurWarning;
import com.mermaid.drug.domain.PrescriptionStatus;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** What the model is shown, and what the validator is told to allow. */
class DrugContextRetrieverTest {

    private static final Instant WHEN = Instant.parse("2026-07-10T05:00:00Z");

    private static final String LONG_CAUTION =
            "만 12세 이하의 소아, 임부 또는 수유부는 의사와 상의할 것. "
                    + "이 약을 복용하는 동안 술을 마시지 마십시오. ".repeat(30);

    private final ObjectMapper mapper = new ObjectMapper();

    private static final SourceRef TYLENOL_SOURCE = new SourceRef(
            "src:mfds:202005623", "식품의약품안전처 의약품 제품 허가정보", "202005623",
            WHEN, SourceRef.DataMode.FIXTURE, "MFDS — drug product licence information");

    private static final Drug TYLENOL = new Drug(
            "drug:mfds:202005623", "202005623", "어린이타이레놀산160밀리그램(아세트아미노펜)", null, "한국얀센",
            List.of("Acetaminophen Granules"), "아세트아미노펜과립", PrescriptionStatus.OTC,
            new Drug.Narrative("이 약은 감기로 인한 발열 및 통증에 사용합니다.", "1일 4회", LONG_CAUTION, null, null, null, null),
            List.of(new DurWarning(DurWarning.Kind.AGE, "197100097", "환인벤즈트로핀정",
                    "Benztropine Mesylate", "3세 미만 소아에 사용하지 말 것", "20140109", null, null)),
            AllergyCheck.noMatch(),
            TYLENOL_SOURCE);

    private DrugContextRetriever retriever(RetrievalQuery extracted, RetrievedContext retrieved) {
        SearchTermExtractor extractor = new SearchTermExtractor(null, mapper) {
            @Override
            public RetrievalQuery extract(String userText) {
                return extracted;
            }
        };
        DrugService drugService = new DrugService(null, null, null, null, null, null, null) {
            @Override
            public RetrievedContext retrieve(RetrievalQuery query, Set<String> avoidedKeys) {
                return retrieved;
            }
        };
        return new DrugContextRetriever(extractor, drugService, new IngredientNormalizer(), mapper);
    }

    private JsonNode contextJson(DrugContext context) throws Exception {
        String message = context.systemMessage();
        return mapper.readTree(message.substring(message.indexOf('[')));
    }

    /** Remembers the query that actually reached retrieval — the gate's only observable effect. */
    private static final class CapturingDrugService extends DrugService {

        private final RetrievedContext result;
        RetrievalQuery seen;
        Set<String> avoidedKeys;

        CapturingDrugService(RetrievedContext result) {
            super(null, null, null, null, null, null, null);
            this.result = result;
        }

        @Override
        public RetrievedContext retrieve(RetrievalQuery query, Set<String> avoidedKeys) {
            this.seen = query;
            this.avoidedKeys = Set.copyOf(avoidedKeys);
            return result;
        }
    }

    private DrugContextRetriever gated(RetrievalQuery extracted, CapturingDrugService drugService) {
        SearchTermExtractor extractor = new SearchTermExtractor(null, mapper) {
            @Override
            public RetrievalQuery extract(String userText) {
                return extracted;
            }
        };
        return new DrugContextRetriever(extractor, drugService, new IngredientNormalizer(), mapper);
    }

    @Nested
    @DisplayName("nothing retrieved")
    class Empty {

        @Test
        @DisplayName("the model is told, in words, that it may name no medicine")
        void saysSoExplicitly() {
            DrugContext context = retriever(RetrievalQuery.EMPTY, RetrievedContext.EMPTY).retrieve("hello", Set.of());

            assertThat(context.systemMessage())
                    .contains("nothing was retrieved")
                    .contains("name no medicine");
            assertThat(context.allowedProductNames()).isEmpty();
            assertThat(context.sources()).isEmpty();
        }

        @Test
        @DisplayName("an empty context still ends in a valid, empty JSON array")
        void stillValidJson() throws Exception {
            DrugContext context = retriever(RetrievalQuery.EMPTY, RetrievedContext.EMPTY).retrieve("hi", Set.of());

            assertThat(contextJson(context)).isEmpty();
        }

        @Test
        @DisplayName("no search terms means the drug service is never asked")
        void shortCircuitsBeforeRetrieval() {
            DrugService neverCalled = new DrugService(null, null, null, null, null, null, null) {
                @Override
                public RetrievedContext retrieve(RetrievalQuery query, Set<String> avoidedKeys) {
                    throw new AssertionError("retrieve() must not be called for an empty query");
                }
            };
            SearchTermExtractor extractor = new SearchTermExtractor(null, mapper) {
                @Override
                public RetrievalQuery extract(String userText) {
                    return RetrievalQuery.EMPTY;
                }
            };

            DrugContext context =
                    new DrugContextRetriever(extractor, neverCalled, new IngredientNormalizer(), mapper)
                            .retrieve("where is the nearest pharmacy?", Set.of());

            assertThat(context.allowedProductNames()).isEmpty();
        }
    }

    @Nested
    @DisplayName("one drug retrieved")
    class Rendered {

        private DrugContext context() {
            RetrievedContext retrieved =
                    new RetrievedContext(List.of(TYLENOL), Set.of(TYLENOL.nameKo()), List.of(TYLENOL_SOURCE));
            return retriever(new RetrievalQuery(List.of("Acetaminophen"), List.of()), retrieved)
                    .retrieve("I have a headache", Set.of());
        }

        @Test
        @DisplayName("the grounding facts and sources come straight from what was retrieved")
        void allowlistAndSources() {
            DrugContext context = context();

            assertThat(context.allowedProductNames()).containsExactly("어린이타이레놀산160밀리그램(아세트아미노펜)");
            assertThat(context.groundedDrugs().get(TYLENOL.nameKo()).sourceRefId())
                    .isEqualTo(TYLENOL_SOURCE.id());
            assertThat(context.groundedDrugs().get(TYLENOL.nameKo()).ingredientKeys())
                    .containsExactly("acetaminophen");
            assertThat(context.sources()).containsExactly(TYLENOL_SOURCE);
        }

        @Test
        @DisplayName("each retrieved product keeps its own source and ingredient identity")
        void preservesEveryProductSourcePair() {
            SourceRef ibuprofenSource = new SourceRef(
                    "src:mfds:ibuprofen", "식품의약품안전처 의약품 제품 허가정보", "ibuprofen",
                    WHEN, SourceRef.DataMode.FIXTURE, "MFDS — drug product licence information");
            Drug ibuprofen = new Drug(
                    "drug:mfds:ibuprofen", "ibuprofen", "부루펜정200밀리그람", null, "삼일제약",
                    List.of("Ibuprofen"), "이부프로펜", PrescriptionStatus.OTC,
                    new Drug.Narrative("통증에 사용합니다.", null, null, null, null, null, null),
                    List.of(), AllergyCheck.noMatch(), ibuprofenSource);
            RetrievedContext retrieved = new RetrievedContext(
                    List.of(TYLENOL, ibuprofen),
                    Set.of(TYLENOL.nameKo(), ibuprofen.nameKo()),
                    List.of(TYLENOL_SOURCE, ibuprofenSource));

            DrugContext context = retriever(
                            new RetrievalQuery(List.of("Acetaminophen", "Ibuprofen"), List.of()),
                            retrieved)
                    .retrieve("I have a headache", Set.of());

            assertThat(context.groundedDrugs().get(TYLENOL.nameKo()).sourceRefId())
                    .isEqualTo(TYLENOL_SOURCE.id());
            assertThat(context.groundedDrugs().get(ibuprofen.nameKo()).sourceRefId())
                    .isEqualTo(ibuprofenSource.id());
            assertThat(context.groundedDrugs().get(ibuprofen.nameKo()).ingredientKeys())
                    .containsExactly("ibuprofen");
        }

        @Test
        @DisplayName("a product with an unnormalisable ingredient is not trusted for validation")
        void rejectsUngroundableIngredientIdentity() {
            SourceRef source = new SourceRef(
                    "src:mfds:ungroundable", "식품의약품안전처 의약품 제품 허가정보", "ungroundable",
                    WHEN, SourceRef.DataMode.FIXTURE, "MFDS — drug product licence information");
            Drug drug = new Drug(
                    "drug:mfds:ungroundable", "ungroundable", "검증불가정", null, "제조사",
                    List.of("Acetaminophen (Caffeine)"), "아세트아미노펜(카페인)", PrescriptionStatus.OTC,
                    new Drug.Narrative("통증에 사용합니다.", null, null, null, null, null, null),
                    List.of(), AllergyCheck.noMatch(), source);
            RetrievedContext retrieved =
                    new RetrievedContext(List.of(drug), Set.of(drug.nameKo()), List.of(source));

            DrugContext context = retriever(
                            new RetrievalQuery(List.of("Acetaminophen"), List.of()), retrieved)
                    .retrieve("I have a headache", Set.of());

            assertThat(context.groundedDrugs()).doesNotContainKey(drug.nameKo());
            assertThat(context.allowedProductNames()).isEmpty();
        }

        @Test
        @DisplayName("a retrieved product with no English ingredient list is still grounded, not dropped (#60)")
        void keepsProductWithNoEnglishIngredients() {
            SourceRef source = new SourceRef(
                    "src:mfds:noingredients", "식품의약품안전처 의약품 제품 허가정보", "noingredients",
                    WHEN, SourceRef.DataMode.FIXTURE, "MFDS — drug product licence information");
            Drug drug = new Drug(
                    "drug:mfds:noingredients", "성분미상정", "no-ingredient product", null, "제조사",
                    List.of(), null, PrescriptionStatus.OTC,
                    new Drug.Narrative("통증에 사용합니다.", null, null, null, null, null, null),
                    List.of(), AllergyCheck.noMatch(), source);
            RetrievedContext retrieved =
                    new RetrievedContext(List.of(drug), Set.of(drug.nameKo()), List.of(source));

            DrugContext context = retriever(
                            new RetrievalQuery(List.of("Acetaminophen"), List.of()), retrieved)
                    .retrieve("I have a headache", Set.of());

            // A real retrieved product must not be dropped just because it has no English
            // ingredients, or INV6 refuses a card we actually fetched (#60). Its grounded set is
            // empty, so INV6 still rejects any card that invents ingredients for it.
            assertThat(context.groundedDrugs()).containsKey(drug.nameKo());
            assertThat(context.allowedProductNames()).contains(drug.nameKo());
            assertThat(context.groundedDrugs().get(drug.nameKo()).ingredientKeys()).isEmpty();
        }

        @Test
        @DisplayName("every field the model needs to fill a drug card is present")
        void carriesEverythingTheCardNeeds() throws Exception {
            JsonNode drug = contextJson(context()).get(0);

            assertThat(drug.get("productNameKo").asText()).isEqualTo("어린이타이레놀산160밀리그램(아세트아미노펜)");
            assertThat(drug.get("sourceRefId").asText()).isEqualTo("src:mfds:202005623");
            assertThat(drug.get("prescriptionStatus").asText()).isEqualTo("otc");
            assertThat(drug.get("ingredientsEn")).hasSize(1);
            assertThat(drug.get("officialTextKo").get("efficacy").asText()).contains("발열");
            assertThat(drug.get("durWarnings").get(0).asText()).contains("Benztropine Mesylate");
            assertThat(drug.get("allergyCheck").get("status").asText()).isEqualTo("no_match_found");
        }

        @Test
        @DisplayName("the ministry's text is passed whole — a 주의사항 cut in half is a warning nobody sees")
        void officialTextIsNotTruncated() throws Exception {
            JsonNode drug = contextJson(context()).get(0);

            assertThat(drug.get("officialTextKo").get("caution").asText())
                    .hasSize(LONG_CAUTION.length())
                    .isEqualTo(LONG_CAUTION);
        }

        @Test
        @DisplayName("absent narrative fields are omitted rather than sent as null")
        void omitsMissingFields() throws Exception {
            JsonNode official = contextJson(context()).get(0).get("officialTextKo");

            assertThat(official.has("efficacy")).isTrue();
            assertThat(official.has("sideEffect")).isFalse();
        }

        @Test
        @DisplayName("the preamble names the count and forbids every other product")
        void preambleConstrainsTheModel() {
            assertThat(context().systemMessage())
                    .contains("1 product(s)")
                    .contains("ONLY medicines you may name")
                    .contains("Leave the top-level `source_refs` as an empty array")
                    .contains("Never describe a medicine as safe");
        }
    }

    /**
     * 나르펜정400밀리그램 really does carry twenty 병용금기 records. Sent whole, the model wrote a card with
     * twenty-six warnings naming Korean medicines the reader has never heard of and may not be taking.
     */
    @Nested
    @DisplayName("병용금기 is a property of a pair, not of a medicine")
    class CombinationWarnings {

        private JsonNode render(DurWarning... warnings) throws Exception {
            Drug drug = new Drug(
                    "drug:mfds:198701721", "198701721", "나르펜정400밀리그램(이부프로펜)", null, "제조사",
                    List.of("Ibuprofen"), "이부프로펜", PrescriptionStatus.OTC,
                    new Drug.Narrative("두통", "1일 3회", null, null, null, null, null),
                    List.of(warnings), AllergyCheck.noMatch(), TYLENOL_SOURCE);
            DrugContext context = retriever(
                            new RetrievalQuery(List.of("Ibuprofen"), List.of()),
                            new RetrievedContext(List.of(drug), Set.of(drug.nameKo()), List.of(TYLENOL_SOURCE)))
                    .retrieve("I have a headache", Set.of());
            String message = context.systemMessage();
            return mapper.readTree(message.substring(message.indexOf('['))).get(0);
        }

        private DurWarning of(DurWarning.Kind kind, String paired) {
            return new DurWarning(kind, "999", paired, "Ibuprofen", "금기", "20140109", null, null);
        }

        @Test
        @DisplayName("combination records become a count, not twenty drug names")
        void combinationsAreCounted() throws Exception {
            JsonNode drug = render(
                    of(DurWarning.Kind.COMBINATION, "아스피린정"),
                    of(DurWarning.Kind.COMBINATION, "케토프로펜정"),
                    of(DurWarning.Kind.PREGNANCY, "나르펜정"));

            assertThat(drug.get("combinationContraindicationCount").asInt()).isEqualTo(2);
            assertThat(drug.get("durWarnings")).hasSize(1);
            assertThat(drug.get("durWarnings").get(0).asText()).contains("Contraindicated during pregnancy");
            assertThat(drug.get("durWarnings").toString()).doesNotContain("아스피린정");
        }

        @Test
        @DisplayName("age, pregnancy and elderly records are still passed whole")
        void otherKindsSurvive() throws Exception {
            JsonNode drug = render(
                    of(DurWarning.Kind.AGE, "x"), of(DurWarning.Kind.PREGNANCY, "y"),
                    of(DurWarning.Kind.ELDERLY, "z"));

            assertThat(drug.get("durWarnings")).hasSize(3);
            assertThat(drug.has("combinationContraindicationCount")).isFalse();
        }

        @Test
        @DisplayName("with no combination records the field is absent, so the model says nothing")
        void noCombinationsNoField() throws Exception {
            assertThat(render().has("combinationContraindicationCount")).isFalse();
        }

        @Test
        @DisplayName("the model is told what to do with the count, and told not to invent the list")
        void preambleExplainsTheCount() throws Exception {
            Drug drug = new Drug(
                    "drug:mfds:1", "1", "나르펜정400밀리그램(이부프로펜)", null, "제조사", List.of("Ibuprofen"), null,
                    PrescriptionStatus.OTC, new Drug.Narrative("두통", null, null, null, null, null, null),
                    List.of(of(DurWarning.Kind.COMBINATION, "아스피린정")), AllergyCheck.noMatch(), TYLENOL_SOURCE);

            String message = retriever(
                            new RetrievalQuery(List.of("Ibuprofen"), List.of()),
                            new RetrievedContext(List.of(drug), Set.of(drug.nameKo()), List.of(TYLENOL_SOURCE)))
                    .retrieve("headache", Set.of())
                    .systemMessage();

            assertThat(message)
                    .contains("combinationContraindicationCount")
                    .contains("tell a pharmacist")
                    .contains("Do not invent the list");
        }
    }

    /**
     * The model proposes ingredients. Asked for a headache remedy by someone allergic to ibuprofen, a
     * live model proposed <b>Naproxen</b> — another NSAID, and a real product our allergy check reports
     * as {@code no_match_found} because it matches ingredient names, not drug families. Nothing was
     * hallucinated, so no invariant fires.
     *
     * <p>We hold no reviewed drug-class table and will not invent one (spec §2-12). So when an allergy
     * is declared, the model does not get to choose a medicine at all.
     */
    @Nested
    @DisplayName("a declared allergy takes the choice of medicine away from the model")
    class AllergyGate {

        private static final RetrievalQuery PROPOSED =
                new RetrievalQuery(
                        List.of("Acetaminophen", "Naproxen"),
                        List.of(),
                        List.of("ibuprofen"));

        @Test
        @DisplayName("the naproxen regression: a proposed ingredient never reaches retrieval")
        void proposedIngredientsNeverReachRetrieval() {
            CapturingDrugService drugService = new CapturingDrugService(RetrievedContext.EMPTY);

            gated(PROPOSED, drugService)
                    .retrieve("I have a headache but I am allergic to ibuprofen", Set.of());

            assertThat(drugService.seen)
                    .as("retrieval must not be asked for anything the model proposed")
                    .isNull();
        }

        @Test
        @DisplayName("the request field gates on its own — the sentence need not mention the allergy")
        void excludeIngredientsFieldAlsoGates() {
            CapturingDrugService drugService = new CapturingDrugService(RetrievedContext.EMPTY);

            gated(PROPOSED, drugService).retrieve("I have a headache", Set.of("Ibuprofen"));

            assertThat(drugService.seen).isNull();
        }

        @Test
        @DisplayName("SC-001: an unresolved declared allergy returns the server clarification")
        void unresolvedAllergyFailsClosedBeforeRetrieval() throws Exception {
            CapturingDrugService drugService = new CapturingDrugService(RetrievedContext.EMPTY);
            RetrievalQuery unresolved = new RetrievalQuery(
                    List.of("Naproxen"), List.of(), List.of("notarealingredientxyz"));

            DrugContext context = gated(unresolved, drugService)
                    .retrieve("I am allergic to notarealingredientxyz", Set.of());

            MermAidAnswer answer = context.directAnswer().orElseThrow();
            assertThat(drugService.seen).as("fail-closed must happen before retrieval").isNull();
            assertThat(answer.clarifyingQuestions()).containsExactly(AllergyClarification.QUESTION);
            assertThat(answer.drugs()).isEmpty();
            assertThat(answer.disclaimer()).isEqualTo(StructuredOutputFallback.DISCLAIMER);
            assertThat(mapper.writeValueAsString(answer))
                    .doesNotContain("no_match_found", "Naproxen");

            CapturingDrugService unsignedExtensionService =
                    new CapturingDrugService(RetrievedContext.EMPTY);
            DrugContext unsignedExtension = gated(RetrievalQuery.EMPTY, unsignedExtensionService)
                    .retrieve("I have a headache", Set.of("paracetamol"));

            assertThat(unsignedExtension.directAnswer()).isPresent();
            assertThat(unsignedExtensionService.seen).isNull();
        }

        @Test
        @DisplayName("a mixed declaration — one resolved, one unresolved — still fails closed (#59 follow-up P0)")
        void mixedResolvedAndUnresolvedAllergyFailsClosed() throws Exception {
            // aspirin is a signed synonyms.tsv row (resolves); paracetamol is unsigned (unresolved).
            // The old gate only fired when the whole avoided set was empty, so a mix passed and the
            // unresolved allergen was silently dropped — a paracetamol drug could then show
            // no_match_found. ANY unresolved declared allergen must fail closed.
            CapturingDrugService freeText = new CapturingDrugService(RetrievedContext.EMPTY);
            RetrievalQuery mixed = new RetrievalQuery(
                    List.of("Naproxen"), List.of(), List.of("aspirin", "paracetamol"));

            DrugContext context = gated(mixed, freeText)
                    .retrieve("I am allergic to aspirin and paracetamol", Set.of());

            MermAidAnswer answer = context.directAnswer().orElseThrow();
            assertThat(freeText.seen).as("fail-closed must happen before retrieval").isNull();
            assertThat(answer.clarifyingQuestions()).containsExactly(AllergyClarification.QUESTION);
            assertThat(answer.drugs()).isEmpty();
            assertThat(mapper.writeValueAsString(answer)).doesNotContain("no_match_found", "Naproxen");

            // Same mix via the exclude_ingredients field.
            CapturingDrugService viaField = new CapturingDrugService(RetrievedContext.EMPTY);
            DrugContext fieldContext = gated(RetrievalQuery.EMPTY, viaField)
                    .retrieve("I have a headache", Set.of("aspirin", "paracetamol"));

            assertThat(fieldContext.directAnswer()).isPresent();
            assertThat(viaField.seen).isNull();
        }

        @Test
        @DisplayName("an allergen list at the extraction cap fails closed — the model may have clipped one (#62 P0, FR-012)")
        void allergenListAtCapFailsClosed() {
            // The extractor caps allergens; a list at the cap may be clipped, and a clipped allergen
            // is exactly the silently-dropped case the unresolved check cannot see. The server can't
            // know what the model omitted, so reaching the cap is a hard clarify — even if every
            // extracted name resolves.
            CapturingDrugService drugService = new CapturingDrugService(RetrievedContext.EMPTY);
            RetrievalQuery query = new RetrievalQuery(
                    List.of("Naproxen"), List.of(), List.of("aspirin"), true);

            DrugContext context = gated(query, drugService).retrieve("I am allergic to aspirin", Set.of());

            assertThat(context.directAnswer()).as("cap reached must fail closed").isPresent();
            assertThat(drugService.seen).as("fail-closed must happen before retrieval").isNull();
        }

        @Test
        @DisplayName("with no allergy the model's ingredients pass through untouched")
        void noAllergyNoGate() {
            CapturingDrugService drugService = new CapturingDrugService(RetrievedContext.EMPTY);

            gated(PROPOSED, drugService).retrieve("I have a headache", Set.of());

            assertThat(drugService.seen.ingredientsEn()).containsExactly("Acetaminophen", "Naproxen");
        }

        @Test
        @DisplayName("a product the person named themselves is still looked up")
        void userNamedProductSurvivesTheGate() {
            CapturingDrugService drugService = new CapturingDrugService(RetrievedContext.EMPTY);

            gated(new RetrievalQuery(
                                    List.of("Naproxen"),
                                    List.of("부루펜"),
                                    List.of("ibuprofen")),
                            drugService)
                    .retrieve("I'm allergic to ibuprofen — can I take 부루펜?", Set.of());

            assertThat(drugService.seen).isNotNull();
            assertThat(drugService.seen.ingredientsEn()).isEmpty();
            assertThat(drugService.seen.productNamesKo()).containsExactly("부루펜");
            assertThat(drugService.avoidedKeys).containsExactly("ibuprofen");
        }

        @Test
        @DisplayName("\"we refused to look\" is not rendered as \"we found nothing\"")
        void preambleExplainsTheRefusal() {
            String message = gated(PROPOSED, new CapturingDrugService(RetrievedContext.EMPTY))
                    .retrieve("I am allergic to ibuprofen, my head hurts", Set.of())
                    .systemMessage();

            assertThat(message)
                    .contains("you must not name one")
                    .contains("cannot suggest an alternative")
                    .contains("name no medicine");
        }

        @Test
        @DisplayName("without an allergy an empty context keeps its old wording")
        void plainEmptyIsUnchanged() {
            String message = gated(RetrievalQuery.EMPTY, new CapturingDrugService(RetrievedContext.EMPTY))
                    .retrieve("hello", Set.of())
                    .systemMessage();

            assertThat(message)
                    .contains("nothing was retrieved")
                    .doesNotContain("cannot suggest an alternative");
        }

        @Test
        @DisplayName("when the person named a drug, the model is told not to gesture at alternatives")
        void groundedContextForbidsAlternatives() {
            Drug blocked = new Drug(
                    "drug:mfds:198701721", "198701721", "부루펜정400밀리그램", null, "삼일제약",
                    List.of("Ibuprofen"), "이부프로펜", PrescriptionStatus.OTC,
                    new Drug.Narrative("두통", "1일 3회", null, null, null, null, null),
                    List.of(),
                    new AllergyCheck(
                            AllergyCheck.Status.BLOCKED,
                            List.of("Ibuprofen"),
                            "Contains Ibuprofen, which you asked to avoid."),
                    TYLENOL_SOURCE);
            RetrievedContext retrieved =
                    new RetrievedContext(List.of(blocked), Set.of(blocked.nameKo()), List.of(TYLENOL_SOURCE));

            String message = gated(new RetrievalQuery(
                                            List.of("Naproxen"),
                                            List.of("부루펜"),
                                            List.of("ibuprofen")),
                                    new CapturingDrugService(retrieved))
                    .retrieve("I'm allergic to ibuprofen — can I take 부루펜?", Set.of())
                    .systemMessage();

            assertThat(message)
                    .contains("1 product(s)")
                    .contains("Do not suggest an alternative medicine");
        }
    }
}
