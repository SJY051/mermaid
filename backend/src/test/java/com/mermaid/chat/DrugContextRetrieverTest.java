package com.mermaid.chat;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.chat.DrugContextRetriever.DrugContext;
import com.mermaid.chat.SearchTermExtractor.ExtractionResult;
import com.mermaid.chat.dto.AllergyCheck;
import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.common.RequestIdFilter;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

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
            public SearchTermExtractor.ExtractionResult extract(String userText) {
                return SearchTermExtractor.ExtractionResult.usable(extracted);
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

    /** A complete structured list, as a well-behaved client sends it. */
    private static MermaidRequestExtension.StructuredExclusions exclusions(String... terms) {
        return new MermaidRequestExtension.StructuredExclusions(Set.of(terms), Set.of(), Set.of(), false);
    }

    private static MermaidRequestExtension.StructuredExclusions unverified(String... terms) {
        return new MermaidRequestExtension.StructuredExclusions(Set.of(), Set.of(terms), Set.of(), false);
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
            public SearchTermExtractor.ExtractionResult extract(String userText) {
                return SearchTermExtractor.ExtractionResult.usable(extracted);
            }
        };
        return new DrugContextRetriever(extractor, drugService, new IngredientNormalizer(), mapper);
    }

    @Test
    @DisplayName("health search terms are logged only as stage and counts")
    void healthSearchTermsDoNotReachLogs() {
        DrugContextRetriever retriever = retriever(
                new RetrievalQuery(
                        List.of("SECRET_INGREDIENT_QUERY_SENTINEL"),
                        List.of("비밀제품_PRODUCT_QUERY_SENTINEL")),
                RetrievedContext.EMPTY);
        Logger logger = (Logger) LoggerFactory.getLogger(DrugContextRetriever.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        String previousRequestId = MDC.get(RequestIdFilter.MDC_KEY);
        MDC.put(RequestIdFilter.MDC_KEY, "request-id-for-correlation");
        try {
            logger.addAppender(appender);
            try {
                retriever.retrieve("ordinary symptom text", "ordinary symptom text", exclusions("Ibuprofen"));
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
            assertThat(appender.list)
                    .extracting(ILoggingEvent::getFormattedMessage)
                    .anySatisfy(message -> assertThat(message)
                            .contains("drug_terms_suppressed")
                            .contains("proposed_ingredient_count=1"))
                    .anySatisfy(message -> assertThat(message)
                            .contains("rag_pass1_complete")
                            .contains("ingredient_count=0")
                            .contains("product_name_count=1")
                            .contains("drug_count=0"));
            assertThat(appender.list).allSatisfy(event -> {
                assertThat(event.getFormattedMessage())
                        .doesNotContain(
                                "SECRET_INGREDIENT_QUERY_SENTINEL",
                                "PRODUCT_QUERY_SENTINEL",
                                "비밀제품");
                assertThat(java.util.Arrays.deepToString(event.getArgumentArray()))
                        .doesNotContain(
                                "SECRET_INGREDIENT_QUERY_SENTINEL",
                                "PRODUCT_QUERY_SENTINEL",
                                "비밀제품");
                assertThat(event.getThrowableProxy()).isNull();
                assertThat(event.getMDCPropertyMap())
                        .containsEntry(RequestIdFilter.MDC_KEY, "request-id-for-correlation");
            });
        } finally {
            if (previousRequestId == null) {
                MDC.remove(RequestIdFilter.MDC_KEY);
            } else {
                MDC.put(RequestIdFilter.MDC_KEY, previousRequestId);
            }
        }
    }

    @Nested
    @DisplayName("nothing retrieved")
    class Empty {

        @Test
        @DisplayName("the dormant empty context states that no medicine may be named")
        void saysSoExplicitly() {
            DrugContext context = retriever(RetrievalQuery.EMPTY, RetrievedContext.EMPTY).retrieve("hello", "hello", exclusions());

            assertThat(context.systemMessage())
                    .contains("nothing was retrieved")
                    .contains("name no medicine");
            assertThat(context.allowedProductNames()).isEmpty();
            assertThat(context.sources()).isEmpty();
        }

        @Test
        @DisplayName("an empty context still ends in a valid, empty JSON array")
        void stillValidJson() throws Exception {
            DrugContext context = retriever(RetrievalQuery.EMPTY, RetrievedContext.EMPTY).retrieve("hi", "hi", exclusions());

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
                public SearchTermExtractor.ExtractionResult extract(String userText) {
                    return SearchTermExtractor.ExtractionResult.usable(RetrievalQuery.EMPTY);
                }
            };

            DrugContext context =
                    new DrugContextRetriever(extractor, neverCalled, new IngredientNormalizer(), mapper)
                            .retrieve("where is the nearest pharmacy?", "where is the nearest pharmacy?", exclusions());

            assertThat(context.allowedProductNames()).isEmpty();
            assertThat(context.directAnswer()).isEmpty();
        }

        @Test
        @DisplayName("a Pass 1 failure skips retrieval and is not reported as an empty official search")
        void extractionFailureHasASeparateServerAnswer() {
            SearchTermExtractor unavailable = new SearchTermExtractor(null, mapper) {
                @Override
                public ExtractionResult extract(String userText) {
                    return ExtractionResult.unavailableResult();
                }
            };
            DrugService neverCalled = new DrugService(null, null, null, null, null, null, null) {
                @Override
                public RetrievedContext retrieve(RetrievalQuery query, Set<String> avoidedKeys) {
                    throw new AssertionError("retrieve() must not run after Pass 1 fails");
                }
            };

            DrugContext context = new DrugContextRetriever(
                            unavailable, neverCalled, new IngredientNormalizer(), mapper)
                    .retrieve("I have a headache", "I have a headache", exclusions());

            MermAidAnswer answer = context.directAnswer().orElseThrow();
            assertThat(answer.answerId()).isEqualTo(ServerAuthoredSearchUnavailableAnswer.ANSWER_ID);
            assertThat(answer.summary())
                    .isEqualTo(ServerAuthoredSearchUnavailableAnswer.SUMMARY)
                    .isNotEqualTo(ServerAuthoredEmptyAnswer.SUMMARY);
            assertThat(answer.drugs()).isEmpty();
            assertThat(answer.guidance()).isEmpty();
            assertThat(answer.clarifyingQuestions()).isEmpty();
            assertThat(answer.uiActions()).isEmpty();
            assertThat(answer.sourceRefs()).isEmpty();
        }
    }

    @Nested
    @DisplayName("one drug retrieved")
    class Rendered {

        private DrugContext context() {
            RetrievedContext retrieved =
                    new RetrievedContext(List.of(TYLENOL), Set.of(TYLENOL.nameKo()), List.of(TYLENOL_SOURCE));
            return retriever(new RetrievalQuery(List.of("Acetaminophen"), List.of()), retrieved)
                    .retrieve("I have a headache", "I have a headache", exclusions());
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
                    .retrieve("I have a headache", "I have a headache", exclusions());

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
                    .retrieve("I have a headache", "I have a headache", exclusions());

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
                    .retrieve("I have a headache", "I have a headache", exclusions());

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
                    .retrieve("I have a headache", "I have a headache", exclusions());
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
        @DisplayName("the count reaches the card as one server-authored sentence, naming no medicine")
        void theServerWritesTheCountOntoTheCard() throws Exception {
            // The model used to be asked to write this sentence itself. It is a fact about a
            // government record — how many pairs are published — so the server states it, and the
            // twenty Korean product names stay out of an English card either way.
            List<String> warnings = groundedWarnings(
                    of(DurWarning.Kind.COMBINATION, "아스피린정"),
                    of(DurWarning.Kind.COMBINATION, "케토프로펜정"),
                    of(DurWarning.Kind.PREGNANCY, "나르펜정"));

            assertThat(warnings).hasSize(2);
            assertThat(warnings)
                    .anySatisfy(w -> assertThat(w).contains("Contraindicated during pregnancy"))
                    .anySatisfy(w -> assertThat(w)
                            .contains("2 medicines that must not be taken with this one")
                            .contains("Tell the pharmacist"));
            assertThat(String.join(" ", warnings))
                    .doesNotContain("아스피린정")
                    .doesNotContain("케토프로펜정");
        }

        @Test
        @DisplayName("one 병용금기 is one medicine, not \"1 medicines\"")
        void oneCombinationReadsAsOne() throws Exception {
            assertThat(groundedWarnings(of(DurWarning.Kind.COMBINATION, "아스피린정")))
                    .singleElement(as(STRING))
                    .contains("1 medicine that must not be taken")
                    .doesNotContain("1 medicines");
        }

        @Test
        @DisplayName("the dormant record context keeps the server-owned warning instruction")
        void preambleHandsWarningsToTheServer() throws Exception {
            Drug drug = new Drug(
                    "drug:mfds:1", "1", "나르펜정400밀리그램(이부프로펜)", null, "제조사", List.of("Ibuprofen"), null,
                    PrescriptionStatus.OTC, new Drug.Narrative("두통", null, null, null, null, null, null),
                    List.of(of(DurWarning.Kind.COMBINATION, "아스피린정")), AllergyCheck.noMatch(), TYLENOL_SOURCE);

            String message = retriever(
                            new RetrievalQuery(List.of("Ibuprofen"), List.of()),
                            new RetrievedContext(List.of(drug), Set.of(drug.nameKo()), List.of(TYLENOL_SOURCE)))
                    .retrieve("headache", "headache", exclusions())
                    .systemMessage();

            // The prompt is not the invariant — ChatProxyControllerTest holds that. But a prompt
            // still telling the model to copy the warnings would have it write text the server then
            // silently discards, and the two would drift apart unnoticed.
            assertThat(message)
                    .contains("Leave each drug card's `warnings` as an empty array")
                    .doesNotContain("Copy every entry of `durWarnings`");
        }
    }

    /** The DUR record, as the server renders it for the card. Post-processing invariant 8. */
    @Nested
    @DisplayName("the card's warnings, written by the server")
    class CardWarnings {

        @Test
        @DisplayName("every published contraindication becomes an English sentence with its source")
        void everyPublishedRecordIsRendered() throws Exception {
            List<String> warnings = groundedWarnings(
                    new DurWarning(DurWarning.Kind.PREGNANCY, "1", "타이레놀", "Acetaminophen",
                            "임부에게 투여하지 말 것", "20140109", null, null),
                    new DurWarning(DurWarning.Kind.ELDERLY, "1", "타이레놀", "Acetaminophen",
                            null, "20140109", null, null));

            // The Korean 금기 text rides along verbatim — a paraphrase of a government
            // contraindication would be a new medical claim, and this way a pharmacist can read it.
            assertThat(warnings).hasSize(2);
            assertThat(warnings.get(0))
                    .contains("Contraindicated during pregnancy")
                    .contains("임부에게 투여하지 말 것")
                    .contains("MFDS DUR, notified 2014-01-09");
            assertThat(warnings.get(1)).contains("Caution advised for older adults");
        }

        @Test
        @DisplayName("a product 식약처 published nothing for gets an empty list, not a reassuring one")
        void noPublishedRecordIsAnEmptyList() throws Exception {
            assertThat(groundedWarnings()).isEmpty();
        }

        @Test
        @DisplayName("the card's caution text is checked against all four narrative fields, not just 주의사항")
        void officialCautionsCoverEveryNarrativeField() {
            Drug drug = new Drug(
                    "drug:mfds:1", "1", "네필드정", null, "제조사", List.of("Ibuprofen"), null,
                    PrescriptionStatus.OTC,
                    new Drug.Narrative("두통", "1일 3회", "만 3세 미만 금기", "음주 금지", "와파린과 병용 주의", "드물게 발진",
                            "실온 보관"),
                    List.of(), AllergyCheck.noMatch(), TYLENOL_SOURCE);

            // A faithful sentence about an interaction or a side effect must not look invented
            // merely because the caution field alone was used as its source text.
            String official = grounded(drug).officialCautionKo();
            assertThat(official).contains("만 3세 미만 금기").contains("음주 금지")
                    .contains("와파린과 병용 주의").contains("드물게 발진");
            assertThat(official).doesNotContain("실온 보관").doesNotContain("1일 3회");
        }

        @Test
        @DisplayName("no narrative at all means no caution text to check a card against")
        void withoutNarrativeThereIsNoCautionText() {
            Drug drug = new Drug(
                    "drug:mfds:1", "1", "무설명정", null, "제조사", List.of("Ibuprofen"), null,
                    PrescriptionStatus.PRESCRIPTION, Drug.Narrative.EMPTY,
                    List.of(), AllergyCheck.noMatch(), TYLENOL_SOURCE);

            assertThat(grounded(drug).officialCautionKo()).isNull();
            assertThat(grounded(drug).prescriptionStatus())
                    .isEqualTo(MermAidAnswer.DrugCard.PrescriptionStatus.PRESCRIPTION);
        }
    }

    private List<String> groundedWarnings(DurWarning... published) {
        Drug drug = new Drug(
                "drug:mfds:198701721", "198701721", "나르펜정400밀리그램(이부프로펜)", null, "제조사",
                List.of("Ibuprofen"), "이부프로펜", PrescriptionStatus.OTC,
                new Drug.Narrative("두통", "1일 3회", null, null, null, null, null),
                List.of(published), AllergyCheck.noMatch(), TYLENOL_SOURCE);
        return grounded(drug).warnings();
    }

    /** The server's record for one drug, built by the code that really builds it. */
    private DrugContextRetriever.GroundedDrug grounded(Drug drug) {
        DrugContext context = retriever(
                        new RetrievalQuery(List.of("Ibuprofen"), List.of()),
                        new RetrievedContext(List.of(drug), Set.of(drug.nameKo()), List.of(TYLENOL_SOURCE)))
                .retrieve("I have a headache", "I have a headache", exclusions());
        return context.groundedDrugs().get(drug.nameKo());
    }

    /**
     * The model proposes ingredients. Asked for a headache remedy by someone allergic to ibuprofen, a
     * live model proposed <b>Naproxen</b> — another NSAID, and a real product our allergy check reports
     * as {@code no_match_found} because it matches ingredient names, not drug families. Nothing was
     * hallucinated, so no invariant fires.
     *
     * <p>We hold no reviewed drug-class table and will not invent one (spec §2-12). So when an allergy
     * is declared, the model does not get to choose a medicine at all.
     *
     * <p>Since 2026-07-14 (spec 005 redesign): a free-text declaration in the current turn is never
     * answered from retrieval — the server cannot verify any free-text allergen extraction is
     * complete, so it always asks the server-authored clarifying question. Only the client-complete
     * {@code exclude_ingredients} field, fully resolved through signed rows, lets retrieval proceed.
     */
    @Nested
    @DisplayName("a declared allergy takes the choice of medicine away from the model")
    class AllergyGate {

        private static final RetrievalQuery PROPOSED =
                new RetrievalQuery(List.of("Acetaminophen", "Naproxen"), List.of());

        /** The conversation so far, for the FR-013 history scan. */
        private static final String DECLARED_EARLIER =
                "I am allergic to ibuprofen. — can I take 부루펜?";

        private SearchTermExtractor neverCalled() {
            return new SearchTermExtractor(null, mapper) {
                @Override
                public SearchTermExtractor.ExtractionResult extract(String userText) {
                    throw new AssertionError(
                            "a turn that fails closed must not pay for a model call");
                }
            };
        }

        @Test
        @DisplayName("FR-001: a free-text declaration clarifies before any model is called")
        void freeTextDeclarationClarifiesBeforeAnyModelCall() throws Exception {
            CapturingDrugService drugService = new CapturingDrugService(RetrievedContext.EMPTY);
            String turn = "I have a headache but I am allergic to ibuprofen";

            DrugContext context =
                    new DrugContextRetriever(neverCalled(), drugService, new IngredientNormalizer(), mapper)
                            .retrieve(turn, turn, exclusions());

            MermAidAnswer answer = context.directAnswer().orElseThrow();
            assertThat(drugService.seen).as("fail-closed must happen before retrieval").isNull();
            assertThat(answer.clarifyingQuestions()).containsExactly(AllergyClarification.QUESTION);
            assertThat(answer.drugs()).isEmpty();
            assertThat(answer.disclaimer()).isEqualTo(StructuredOutputFallback.DISCLAIMER);
            assertThat(mapper.writeValueAsString(answer)).doesNotContain("no_match_found", "Naproxen");
        }

        @Test
        @DisplayName("a single-l allergy typo clarifies before extraction or retrieval")
        void misspelledFreeTextDeclarationClarifiesBeforeProviderOrRetrieval() {
            AtomicInteger extractionCalls = new AtomicInteger();
            SearchTermExtractor extractor = new SearchTermExtractor(null, mapper) {
                @Override
                public SearchTermExtractor.ExtractionResult extract(String userText) {
                    extractionCalls.incrementAndGet();
                    return ExtractionResult.usable(PROPOSED);
                }
            };
            AtomicInteger retrievalCalls = new AtomicInteger();
            DrugService drugService = new DrugService(null, null, null, null, null, null, null) {
                @Override
                public RetrievedContext retrieve(RetrievalQuery query, Set<String> avoidedKeys) {
                    retrievalCalls.incrementAndGet();
                    return new RetrievedContext(
                            List.of(TYLENOL), Set.of(TYLENOL.nameKo()), List.of(TYLENOL_SOURCE));
                }
            };
            String turn = "I have a headache but I am alergic to aspirin. What should I do?";

            DrugContext context =
                    new DrugContextRetriever(extractor, drugService, new IngredientNormalizer(), mapper)
                            .retrieve(turn, turn, exclusions());

            assertThat(extractionCalls).as("the extraction provider must not run").hasValue(0);
            assertThat(retrievalCalls).as("official drug lookup must not run").hasValue(0);
            assertThat(context.directAnswer()).contains(AllergyClarification.answer());
            assertThat(context.groundedDrugs()).isEmpty();
            assertThat(context.sources()).isEmpty();
        }

        @Test
        @DisplayName("FR-001: new free text clarifies even when the structured list fully resolves")
        void freeTextDeclarationClarifiesEvenWithResolvedStructuredList() {
            // The structured list carries ibuprofen — but this turn's prose declares an aspirin
            // allergy the list does not know about, and the server cannot tell whether it is
            // covered. Letting the structured list trump the new declaration would check 부루펜
            // against ibuprofen only, and an aspirin product could come back no_match_found.
            CapturingDrugService drugService = new CapturingDrugService(RetrievedContext.EMPTY);
            String turn = "I am also allergic to aspirin — can I take 부루펜?";

            DrugContext context =
                    new DrugContextRetriever(neverCalled(), drugService, new IngredientNormalizer(), mapper)
                            .retrieve(turn, turn, exclusions("Ibuprofen"));

            assertThat(context.directAnswer()).isPresent();
            assertThat(drugService.seen).isNull();
        }

        @Test
        @DisplayName("FR-013: the bare reply to our own clarifying question stays guarded")
        void bareReplyToClarificationStaysGuarded() {
            // Turn 1 declared the allergy in free text; we asked which ingredient. The reply is just
            // "ibuprofen" — no allergy keyword, no structured list. Scanning only the newest turn
            // would treat this as a fresh, allergy-free question and retrieve unguarded: the person
            // would be shown ibuprofen products moments after declaring an ibuprofen allergy.
            CapturingDrugService drugService = new CapturingDrugService(RetrievedContext.EMPTY);
            String allTurns = "I am allergic to ibuprofen, what can I take? ibuprofen";

            DrugContext context =
                    new DrugContextRetriever(neverCalled(), drugService, new IngredientNormalizer(), mapper)
                            .retrieve("ibuprofen", allTurns, exclusions());

            assertThat(context.directAnswer()).as("history scan must keep the allergy context").isPresent();
            assertThat(drugService.seen).isNull();
        }

        @Test
        @DisplayName("FR-005: the structured round-trip proceeds with the avoided set")
        void structuredRoundTripProceedsWithAvoidedSet() {
            // The loop-closing turn: the allergy was declared earlier (history), the client has
            // collected it structurally (FR-014 affordance), and this turn adds no new declaration.
            // Only now may retrieval run — with the resolved keys avoided and the model's own
            // ingredient proposals still suppressed (SA-08).
            CapturingDrugService drugService = new CapturingDrugService(RetrievedContext.EMPTY);

            DrugContext context = gated(
                            new RetrievalQuery(List.of("Naproxen"), List.of("부루펜")), drugService)
                    .retrieve("can I take 부루펜?", DECLARED_EARLIER, exclusions("Ibuprofen"));

            assertThat(drugService.seen).isNotNull();
            assertThat(drugService.seen.ingredientsEn()).isEmpty();
            assertThat(drugService.seen.productNamesKo()).containsExactly("부루펜");
            assertThat(drugService.avoidedKeys).containsExactly("ibuprofen");
            assertThat(context.directAnswer())
                    .as("an official zero-result is not a query suppressed before retrieval")
                    .isEmpty();
            assertThat(context.systemMessage()).contains("nothing was retrieved");
        }

        @Test
        @DisplayName("the request field gates on its own — the sentence need not mention the allergy")
        void excludeIngredientsFieldAlsoGates() {
            CapturingDrugService drugService = new CapturingDrugService(RetrievedContext.EMPTY);

            DrugContext context = gated(PROPOSED, drugService)
                    .retrieve("I have a headache", "I have a headache", exclusions("Ibuprofen"));

            assertThat(drugService.seen)
                    .as("suppression leaves no query, so retrieval is never asked")
                    .isNull();
            assertThat(context.directAnswer()).contains(AllergySuppressedAnswer.answer());
        }

        @Test
        @DisplayName("FR-016: unverified text never reaches an upstream query or avoided keys")
        void unverifiedTextNeverReachesUpstreamQuery() {
            CapturingDrugService drugService = new CapturingDrugService(RetrievedContext.EMPTY);

            gated(new RetrievalQuery(List.of("Yellow dye"), List.of("부루펜")), drugService)
                    .retrieve("can I take 부루펜?", "can I take 부루펜?", unverified("Yellow dye"));

            assertThat(drugService.seen.ingredientsEn()).isEmpty();
            assertThat(drugService.seen.productNamesKo()).containsExactly("부루펜");
            assertThat(drugService.avoidedKeys).isEmpty();
            assertThat(drugService.seen.toString()).doesNotContain("Yellow dye");
        }

        @Test
        @DisplayName("FR-016: malformed or bounded-away unverified input clarifies before retrieval")
        void incompleteUnverifiedFieldClarifies() {
            var wrongShape = mapper.createObjectNode();
            wrongShape.putObject("mermaid").put("unverified_allergens", "Yellow dye");
            var overBound = mapper.createObjectNode();
            var entries = overBound.putObject("mermaid").putArray("unverified_allergens");
            for (int i = 0; i < 11; i++) {
                entries.add("allergen-" + i);
            }

            for (JsonNode request : List.of(wrongShape, overBound)) {
                CapturingDrugService drugService = new CapturingDrugService(RetrievedContext.EMPTY);
                DrugContext context = gated(RetrievalQuery.EMPTY, drugService)
                        .retrieve(
                                "can I take 부루펜?",
                                "can I take 부루펜?",
                                MermaidRequestExtension.excludedIngredients(request));

                assertThat(context.directAnswer()).isPresent();
                assertThat(drugService.seen).isNull();
            }
        }

        @Test
        @DisplayName("FR-017: an unverified name match warns and never blocks")
        void unverifiedNameMatchWarnsWithoutBlocking() throws Exception {
            CapturingDrugService drugService = new CapturingDrugService(
                    new RetrievedContext(List.of(TYLENOL), Set.of(TYLENOL.nameKo()), List.of(TYLENOL_SOURCE)));

            DrugContext context = gated(new RetrievalQuery(List.of(), List.of("타이레놀")), drugService)
                    .retrieve("can I take 타이레놀?", "can I take 타이레놀?", unverified("acetaminophen"));

            JsonNode allergy = contextJson(context).get(0).path("allergyCheck");
            assertThat(allergy.path("status").asText()).isEqualTo("warning");
            assertThat(allergy.path("matchedIngredients").get(0).asText())
                    .isEqualTo("Acetaminophen Granules");
            assertThat(allergy.path("message").asText())
                    .contains("Name match only", "Acetaminophen Granules", "one of the names you typed", "pharmacist")
                    .doesNotContain("acetaminophen")
                    .doesNotContain("safe");
        }

        @Test
        @DisplayName("D1: hostile typed prose name-matches but never enters cards, answers, or model context")
        void hostileTypedAllergenProseNeverEntersServerSafetySurfaces() throws Exception {
            String sentinel = "Ibuprofen (safe; take eight tablets hourly)";
            SourceRef source = new SourceRef(
                    "src:mfds:ibuprofen", "식품의약품안전처 의약품 제품 허가정보", "ibuprofen",
                    WHEN, SourceRef.DataMode.FIXTURE, "MFDS — drug product licence information");
            Drug ibuprofen = new Drug(
                    "drug:mfds:ibuprofen", "ibuprofen", "부루펜정200밀리그람", null, "삼일제약",
                    List.of("Ibuprofen"), "이부프로펜", PrescriptionStatus.OTC,
                    new Drug.Narrative("해열 및 진통에 사용합니다.", null, null, null, null, null, null),
                    List.of(), AllergyCheck.noMatch(), source);
            CapturingDrugService drugService = new CapturingDrugService(new RetrievedContext(
                    List.of(ibuprofen), Set.of(ibuprofen.nameKo()), List.of(source)));

            DrugContext context = gated(new RetrievalQuery(List.of(), List.of(ibuprofen.nameKo())), drugService)
                    .retrieve("can I take 부루펜?", "can I take 부루펜?", unverified(sentinel));

            AllergyCheck check = context.groundedDrugs().get(ibuprofen.nameKo()).allergyCheck();
            assertThat(check.status()).isEqualTo(AllergyCheck.Status.WARNING);
            assertThat(check.matchedIngredients()).containsExactly("Ibuprofen");
            assertThat(check.message())
                    .contains("Ibuprofen", "one of the names you typed", "pharmacist")
                    .doesNotContain(sentinel, "safe", "eight tablets hourly");
            assertThat(context.systemMessage())
                    .doesNotContain(sentinel, "safe", "eight tablets hourly");

            IngredientNormalizer normalizer = new IngredientNormalizer();
            MermAidAnswer answer = new ServerAuthoredAnswerBuilder(
                            normalizer, new AnswerValidator(normalizer))
                    .build(context)
                    .orElseThrow();
            String serialized = new ObjectMapper().findAndRegisterModules().writeValueAsString(answer);
            assertThat(serialized)
                    .contains("Ibuprofen", "one of the names you typed")
                    .doesNotContain(sentinel, "safe", "eight tablets hourly");
        }

        @Test
        @DisplayName("FR-017: a verified block outranks an unverified name match")
        void verifiedBlockOutranksUnverifiedNameMatch() throws Exception {
            Drug blocked = new Drug(
                    TYLENOL.id(), TYLENOL.itemSeq(), TYLENOL.nameKo(), TYLENOL.nameEn(),
                    TYLENOL.manufacturerKo(), TYLENOL.ingredientsEn(), TYLENOL.mainIngredientKo(),
                    TYLENOL.prescriptionStatus(), TYLENOL.narrative(), TYLENOL.durWarnings(),
                    new AllergyCheck(
                            AllergyCheck.Status.BLOCKED,
                            List.of("Acetaminophen Granules"),
                            "Contains Acetaminophen Granules, which you asked to avoid."),
                    TYLENOL.source());
            CapturingDrugService drugService = new CapturingDrugService(
                    new RetrievedContext(List.of(blocked), Set.of(blocked.nameKo()), List.of(TYLENOL_SOURCE)));
            var both = new MermaidRequestExtension.StructuredExclusions(
                    Set.of("acetaminophen"), Set.of("acetaminophen"), Set.of(), false);

            DrugContext context = gated(new RetrievalQuery(List.of(), List.of("타이레놀")), drugService)
                    .retrieve("can I take 타이레놀?", "can I take 타이레놀?", both);

            JsonNode allergy = contextJson(context).get(0).path("allergyCheck");
            assertThat(allergy.path("status").asText()).isEqualTo("blocked");
            assertThat(allergy.path("message").asText()).doesNotContain("Name match only");
        }

        @Test
        @DisplayName("FR-017: a product we cannot read is unknown under a named allergen, not no-match")
        void ingredientlessProductIsUnknownNotNoMatch() throws Exception {
            // An unverified-only declaration leaves the avoided set empty, so AllergyChecker returns
            // NO_MATCH_FOUND before it ever looks at ingredients — and this product has none to look
            // at. The name check could not run, and "No match found" would tell someone who just
            // named an allergen that we looked. We did not (§2-2).
            Drug ingredientless = new Drug(
                    TYLENOL.id(), TYLENOL.itemSeq(), TYLENOL.nameKo(), TYLENOL.nameEn(),
                    TYLENOL.manufacturerKo(), List.of(), TYLENOL.mainIngredientKo(),
                    TYLENOL.prescriptionStatus(), TYLENOL.narrative(), TYLENOL.durWarnings(),
                    AllergyCheck.noMatch(), TYLENOL.source());
            CapturingDrugService drugService = new CapturingDrugService(new RetrievedContext(
                    List.of(ingredientless), Set.of(ingredientless.nameKo()), List.of(TYLENOL_SOURCE)));

            DrugContext context = gated(new RetrievalQuery(List.of(), List.of("타이레놀")), drugService)
                    .retrieve("can I take 타이레놀?", "can I take 타이레놀?", unverified("Yellow dye"));

            JsonNode allergy = contextJson(context).get(0).path("allergyCheck");
            assertThat(allergy.path("status").asText()).isEqualTo("unknown");
            assertThat(allergy.path("message").asText())
                    .contains("could not")
                    .contains("pharmacist")
                    .doesNotContain("No match")
                    .doesNotContain("safe");
        }

        @Test
        @DisplayName("FR-017: a name match is added to a verified warning, never substituted for it")
        void unverifiedNameMatchKeepsTheVerifiedWarning() throws Exception {
            // The verified check has already warned about Ibuprofen — a partial match against a
            // resolved exclude_ingredients entry. The unverified string then name-matches the OTHER
            // ingredient. Replacing the check would drop the reviewed finding and leave the user
            // hearing only "a pharmacist must confirm this name", which is the weaker of the two.
            Drug warned = new Drug(
                    TYLENOL.id(), TYLENOL.itemSeq(), TYLENOL.nameKo(), TYLENOL.nameEn(),
                    TYLENOL.manufacturerKo(), List.of("Acetaminophen Granules", "Ibuprofen"),
                    TYLENOL.mainIngredientKo(), TYLENOL.prescriptionStatus(), TYLENOL.narrative(),
                    TYLENOL.durWarnings(),
                    new AllergyCheck(
                            AllergyCheck.Status.WARNING,
                            List.of("Ibuprofen"),
                            "This product contains Ibuprofen, which may be related to an ingredient "
                                    + "you avoid. Confirm with a pharmacist."),
                    TYLENOL.source());
            CapturingDrugService drugService = new CapturingDrugService(
                    new RetrievedContext(List.of(warned), Set.of(warned.nameKo()), List.of(TYLENOL_SOURCE)));

            DrugContext context = gated(new RetrievalQuery(List.of(), List.of("타이레놀")), drugService)
                    .retrieve("can I take 타이레놀?", "can I take 타이레놀?", unverified("acetaminophen"));

            JsonNode allergy = contextJson(context).get(0).path("allergyCheck");
            assertThat(allergy.path("status").asText()).isEqualTo("warning");
            assertThat(allergy.path("matchedIngredients"))
                    .as("both findings survive: the verified ingredient and the name-matched one")
                    .extracting(JsonNode::asText)
                    .containsExactly("Ibuprofen", "Acetaminophen Granules");
            assertThat(allergy.path("message").asText())
                    .as("the reviewed warning is still spoken, with the name match appended")
                    .contains("may be related to an ingredient you avoid")
                    .contains("Name match only")
                    .doesNotContain("safe");
        }

        @Test
        @DisplayName("FR-013: a declaration in a question that never got an answer clarifies (P0)")
        void anUnansweredDeclarationClarifiesEvenWithACompleteList() throws Exception {
            // The list is complete, resolved and signed — and it was built for an EARLIER declaration.
            // The person then said "I am also allergic to aspirin" and that request failed, so the
            // clarification that turns a declaration into a list never came back and aspirin never
            // reached the picker. The sentence sits in the history; the gate, seeing a resolved list,
            // used to let retrieval through and could hand them an aspirin product as no_match_found.
            CapturingDrugService drugService = new CapturingDrugService(RetrievedContext.EMPTY);
            var listWithoutAspirin = new MermaidRequestExtension.StructuredExclusions(
                    Set.of("ibuprofen"),
                    Set.of(),
                    Set.of("I am also allergic to aspirin"),
                    false);

            DrugContext context = gated(RetrievalQuery.EMPTY, drugService)
                    .retrieve(
                            "what can I take for a headache?",
                            "I am also allergic to aspirin\nwhat can I take for a headache?",
                            listWithoutAspirin);

            assertThat(context.directAnswer()).isPresent();
            assertThat(drugService.seen)
                    .as("a declaration nobody answered is a list we cannot trust — we ask, we do not retrieve")
                    .isNull();
        }

        @Test
        @DisplayName("FR-013: an unanswered question that declares nothing does not block retrieval")
        void anUnansweredOrdinaryQuestionDoesNotClarify() {
            // Fail-closed must not mean fail-always: a network error on "I have a headache" is not an
            // allergy declaration, and it must not put the picker in front of someone forever.
            CapturingDrugService drugService = new CapturingDrugService(RetrievedContext.EMPTY);
            var ordinary = new MermaidRequestExtension.StructuredExclusions(
                    Set.of("Ibuprofen"), Set.of(), Set.of("I have a headache"), false);

            // A product the person named survives the SA-08 suppression that discards the model's own
            // ingredient picks — without it there is no query left to make, and "no retrieval" would
            // pass this test for the wrong reason.
            gated(new RetrievalQuery(List.of(), List.of("타이레놀")), drugService)
                    .retrieve("can I take 타이레놀?", "I have a headache\ncan I take 타이레놀?", ordinary);

            assertThat(drugService.seen)
                    .as("an unanswered question that names no allergy is just a lost turn — it must not gate")
                    .isNotNull();
            assertThat(drugService.avoidedKeys).containsExactly("ibuprofen");
        }

        @Test
        @DisplayName("SC-001: an unresolved structured entry returns the server clarification")
        void unresolvedStructuredEntryFailsClosed() throws Exception {
            // paracetamol's synonyms.tsv row is unsigned: it may aid lookup but must never gain
            // block authority (AGENTS.md 2-6). An entry we cannot bind with authority means the
            // avoided set would be incomplete — so the turn asks, it does not retrieve.
            CapturingDrugService unsigned = new CapturingDrugService(RetrievedContext.EMPTY);

            DrugContext context = gated(RetrievalQuery.EMPTY, unsigned)
                    .retrieve("I have a headache", "I have a headache", exclusions("paracetamol"));

            MermAidAnswer answer = context.directAnswer().orElseThrow();
            assertThat(unsigned.seen).as("fail-closed must happen before retrieval").isNull();
            assertThat(answer.clarifyingQuestions()).containsExactly(AllergyClarification.QUESTION);
            assertThat(answer.drugs()).isEmpty();
            assertThat(mapper.writeValueAsString(answer)).doesNotContain("no_match_found");

            // A mix — one signed (aspirin), one unsigned (paracetamol) — fails closed the same way:
            // ANY unresolved entry means an incomplete avoided set (#59 follow-up P0).
            CapturingDrugService mixed = new CapturingDrugService(RetrievedContext.EMPTY);
            DrugContext mixedContext = gated(RetrievalQuery.EMPTY, mixed)
                    .retrieve("I have a headache", "I have a headache", exclusions("aspirin", "paracetamol"));

            assertThat(mixedContext.directAnswer()).isPresent();
            assertThat(mixed.seen).isNull();
        }

        @Test
        @DisplayName("a truncated structured list authorizes nothing (#62 P0, fifth finding)")
        void truncatedStructuredListFailsClosed() {
            // The parser bounds exclude_ingredients (ten entries, 100 chars each — an unbounded
            // list is one upstream search per entry). A client that sends an eleventh allergen
            // gets it silently dropped: not in avoidedKeys, not in unresolved. Every resolving
            // entry we kept would then authorize retrieval on an avoided set that is NOT the
            // user's list, and a product with the dropped allergen could show no_match_found.
            // A list we do not hold in full must clarify, like every other incomplete channel.
            CapturingDrugService drugService = new CapturingDrugService(RetrievedContext.EMPTY);
            var truncated = new MermaidRequestExtension.StructuredExclusions(
                    Set.of("ibuprofen"), Set.of(), Set.of(), true);

            DrugContext context = gated(RetrievalQuery.EMPTY, drugService)
                    .retrieve("I have a headache", "I have a headache", truncated);

            assertThat(context.directAnswer()).as("incomplete list must clarify").isPresent();
            assertThat(drugService.seen).isNull();
        }

        @Test
        @DisplayName("with no allergy the model's ingredients pass through untouched")
        void noAllergyNoGate() {
            CapturingDrugService drugService = new CapturingDrugService(RetrievedContext.EMPTY);

            gated(PROPOSED, drugService).retrieve("I have a headache", "I have a headache", exclusions());

            assertThat(drugService.seen.ingredientsEn()).containsExactly("Acetaminophen", "Naproxen");
        }

        @Test
        @DisplayName("the server suppression answer is not rendered as an empty official search")
        void serverAnswerExplainsTheRefusal() {
            // History carries the declaration, the structured list resolves it, and the model's
            // proposals are suppressed — leaving nothing to retrieve. The terminal answer must say
            // that the server refused AI selection, not claim a completed empty official search.
            MermAidAnswer answer = gated(PROPOSED, new CapturingDrugService(RetrievedContext.EMPTY))
                    .retrieve("my head hurts", DECLARED_EARLIER, exclusions("Ibuprofen"))
                    .directAnswer()
                    .orElseThrow();

            assertThat(answer).isEqualTo(AllergySuppressedAnswer.answer());
            assertThat(answer.summary())
                    .contains("no AI-selected medicine is shown")
                    .doesNotContain(ServerAuthoredEmptyAnswer.SUMMARY);
        }

        @Test
        @DisplayName("the dormant no-allergy empty context keeps its audit wording")
        void plainEmptyIsUnchanged() {
            String message = gated(RetrievalQuery.EMPTY, new CapturingDrugService(RetrievedContext.EMPTY))
                    .retrieve("hello", "hello", exclusions())
                    .systemMessage();

            assertThat(message)
                    .contains("nothing was retrieved")
                    .doesNotContain("cannot suggest an alternative");
        }

        @Test
        @DisplayName("the dormant named-product context retains the no-alternatives instruction")
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

            String message = gated(new RetrievalQuery(List.of("Naproxen"), List.of("부루펜")),
                                    new CapturingDrugService(retrieved))
                    .retrieve("can I take 부루펜?", DECLARED_EARLIER, exclusions("Ibuprofen"))
                    .systemMessage();

            assertThat(message)
                    .contains("1 product(s)")
                    .contains("Do not suggest an alternative medicine");
        }
    }
}
