package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.mermaid.chat.AnswerValidator.ViolationCode;
import com.mermaid.chat.DrugContextRetriever.DrugContext;
import com.mermaid.chat.DrugContextRetriever.GroundedDrug;
import com.mermaid.chat.dto.AllergyCheck;
import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.common.SourceRef;
import com.mermaid.drug.IngredientNormalizer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ServerAuthoredAnswerBuilderTest {

    private static final SourceRef LIVE_IBUPROFEN = source(
            "src:mfds:ibuprofen", "ibuprofen", SourceRef.DataMode.LIVE, "Ibuprofen record");
    private static final SourceRef FIXTURE_ACETAMINOPHEN = source(
            "src:mfds:acetaminophen",
            "acetaminophen",
            SourceRef.DataMode.FIXTURE,
            "Acetaminophen record");

    private static final String IBUPROFEN_KO = "이부프로펜정200밀리그램";
    private static final String ACETAMINOPHEN_KO = "아세트아미노펜정500밀리그램";
    private static final String MODEL_PROSE_SENTINEL = "MODEL_PROSE_MUST_NOT_SURVIVE";

    private final IngredientNormalizer normalizer = new IngredientNormalizer();
    private final AnswerValidator validator = new AnswerValidator(normalizer);
    private final ServerAuthoredAnswerBuilder builder =
            new ServerAuthoredAnswerBuilder(normalizer, validator);

    @Test
    @DisplayName("canonical cards follow source order and contain server records only")
    void buildsCanonicalCardsInSourceOrder() {
        AllergyCheck ibuprofenAllergy = new AllergyCheck(
                AllergyCheck.Status.BLOCKED,
                List.of("Ibuprofen"),
                "Contains Ibuprofen, which you asked to avoid.");
        GroundedDrug ibuprofen = grounded(
                LIVE_IBUPROFEN.id(),
                Set.of("ibuprofen"),
                ibuprofenAllergy,
                "Ibuprofen Tablets",
                // The card keeps the ministry display name but derives the comparison key through
                // IngredientNormalizer; lower-casing alone would incorrectly keep "lysine".
                List.of("Ibuprofen Lysine"),
                "성인 1회 1정, 1일 3회 복용합니다.",
                MODEL_PROSE_SENTINEL,
                MermAidAnswer.DrugCard.PrescriptionStatus.PRESCRIPTION,
                List.of("Do not use during the third trimester of pregnancy."),
                MODEL_PROSE_SENTINEL);
        GroundedDrug acetaminophen = grounded(
                FIXTURE_ACETAMINOPHEN.id(),
                Set.of("acetaminophen", "caffeine"),
                AllergyCheck.noMatch(),
                "Acetaminophen and Caffeine Tablets",
                List.of("Acetaminophen", "Caffeine"),
                "   ",
                MODEL_PROSE_SENTINEL,
                MermAidAnswer.DrugCard.PrescriptionStatus.OTC,
                List.of("Tell a pharmacist about other medicines you take."),
                MODEL_PROSE_SENTINEL);

        // Deliberately opposite to source order. Map iteration must never decide card order.
        Map<String, GroundedDrug> mapOrder = new LinkedHashMap<>();
        mapOrder.put(ACETAMINOPHEN_KO, acetaminophen);
        mapOrder.put(IBUPROFEN_KO, ibuprofen);
        DrugContext context = new DrugContext(
                "DRUG_CONTEXT: " + MODEL_PROSE_SENTINEL,
                mapOrder,
                List.of(LIVE_IBUPROFEN, FIXTURE_ACETAMINOPHEN));

        Optional<MermAidAnswer> built = builder.build(context);

        assertThat(built).hasValueSatisfying(answer -> {
            assertThat(answer.schemaVersion()).isEqualTo(MermAidAnswer.SCHEMA_VERSION);
            assertThat(answer.answerId()).isEqualTo(ServerAuthoredAnswerBuilder.ANSWER_ID);
            assertThat(answer.language()).isEqualTo("en");
            assertThat(answer.dataStatus()).isEqualTo(MermAidAnswer.DataStatus.MIXED);
            assertThat(answer.summary()).isEqualTo(ServerAuthoredAnswerBuilder.SUMMARY);
            assertThat(answer.summary())
                    .contains("English indication and caution explanations are temporarily unavailable")
                    .contains("licensed pharmacist or doctor before taking any medicine")
                    .doesNotContain(MODEL_PROSE_SENTINEL)
                    .doesNotContainIgnoringCase("safe");
            assertThat(answer.clarifyingQuestions()).isEmpty();
            assertThat(answer.guidance()).isEmpty();
            assertThat(answer.uiActions()).isEmpty();
            assertThat(answer.warnings()).isEmpty();
            assertThat(answer.sourceRefs())
                    .containsExactly(LIVE_IBUPROFEN, FIXTURE_ACETAMINOPHEN);
            assertThat(answer.disclaimer()).isEqualTo(StructuredOutputFallback.DISCLAIMER);

            assertThat(answer.urgency().level()).isEqualTo(MermAidAnswer.Urgency.Level.UNKNOWN);
            assertThat(answer.urgency().title())
                    .isEqualTo(ServerAuthoredAnswerBuilder.URGENCY_TITLE);
            assertThat(answer.urgency().message())
                    .isEqualTo(ServerAuthoredAnswerBuilder.URGENCY_MESSAGE)
                    .doesNotContain(MODEL_PROSE_SENTINEL)
                    .doesNotContainIgnoringCase("safe");
            assertThat(answer.urgency().reasonCodes()).isEmpty();
            assertThat(answer.urgency().actions()).isEmpty();

            assertThat(answer.drugs()).extracting(MermAidAnswer.DrugCard::productNameKo)
                    .containsExactly(IBUPROFEN_KO, ACETAMINOPHEN_KO);

            MermAidAnswer.DrugCard first = answer.drugs().get(0);
            assertThat(first.id())
                    .isEqualTo(ServerAuthoredAnswerBuilder.CARD_ID_PREFIX + LIVE_IBUPROFEN.id());
            assertThat(first.productNameEn()).isEqualTo("Ibuprofen Tablets");
            assertThat(first.ingredients()).containsExactly(
                    new MermAidAnswer.Ingredient(
                            null, "Ibuprofen Lysine", "ibuprofen", null, null));
            assertThat(first.indicationSummary()).isNull();
            assertThat(first.directionsSummary()).isEqualTo("성인 1회 1정, 1일 3회 복용합니다.");
            assertThat(first.labelCautions()).isNull();
            assertThat(first.warnings())
                    .containsExactly("Do not use during the third trimester of pregnancy.");
            assertThat(first.prescriptionStatus())
                    .isEqualTo(MermAidAnswer.DrugCard.PrescriptionStatus.PRESCRIPTION);
            assertThat(first.allergyCheck()).isEqualTo(ibuprofenAllergy);
            assertThat(first.sourceRefId()).isEqualTo(LIVE_IBUPROFEN.id());

            MermAidAnswer.DrugCard second = answer.drugs().get(1);
            assertThat(second.id())
                    .isEqualTo(
                            ServerAuthoredAnswerBuilder.CARD_ID_PREFIX
                                    + FIXTURE_ACETAMINOPHEN.id());
            assertThat(second.productNameEn()).isEqualTo("Acetaminophen and Caffeine Tablets");
            assertThat(second.ingredients()).containsExactly(
                    new MermAidAnswer.Ingredient(null, "Acetaminophen", "acetaminophen", null, null),
                    new MermAidAnswer.Ingredient(null, "Caffeine", "caffeine", null, null));
            assertThat(second.indicationSummary()).isNull();
            assertThat(second.directionsSummary()).isNull();
            assertThat(second.labelCautions()).isNull();
            assertThat(second.warnings())
                    .containsExactly("Tell a pharmacist about other medicines you take.");
            assertThat(second.prescriptionStatus())
                    .isEqualTo(MermAidAnswer.DrugCard.PrescriptionStatus.OTC);
            assertThat(second.allergyCheck()).isEqualTo(AllergyCheck.noMatch());
            assertThat(second.sourceRefId()).isEqualTo(FIXTURE_ACETAMINOPHEN.id());

            assertThat(answer.drugs().toString()).doesNotContain(MODEL_PROSE_SENTINEL);
            assertThat(validator.validate(answer, context.groundedDrugs())).isEmpty();
        });
    }

    @Test
    @DisplayName("all-live sources produce live status and all-fixture sources produce fixture status")
    void dataStatusComesOnlyFromServerSources() {
        SourceRef secondLive = source(
                "src:mfds:acetaminophen-live",
                "acetaminophen-live",
                SourceRef.DataMode.LIVE,
                "Acetaminophen live record");
        DrugContext live = context(
                List.of(LIVE_IBUPROFEN, secondLive),
                Map.of(
                        IBUPROFEN_KO, groundedFor(LIVE_IBUPROFEN, "Ibuprofen"),
                        ACETAMINOPHEN_KO, groundedFor(secondLive, "Acetaminophen")));
        DrugContext fixture = context(
                List.of(FIXTURE_ACETAMINOPHEN),
                Map.of(ACETAMINOPHEN_KO, groundedFor(FIXTURE_ACETAMINOPHEN, "Acetaminophen")));

        assertThat(builder.build(live)).get().extracting(MermAidAnswer::dataStatus)
                .isEqualTo(MermAidAnswer.DataStatus.LIVE);
        assertThat(builder.build(fixture)).get().extracting(MermAidAnswer::dataStatus)
                .isEqualTo(MermAidAnswer.DataStatus.FIXTURE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("inconsistentContexts")
    @DisplayName("missing, duplicate, mismatched, or inconsistent records fail closed")
    void inconsistentRecordsReturnNoAnswer(String reason, DrugContext context) {
        assertThat(builder.build(context)).as(reason).isEmpty();
    }

    static Stream<Arguments> inconsistentContexts() {
        GroundedDrug ibuprofen = groundedFor(LIVE_IBUPROFEN, "Ibuprofen");
        SourceRef other = source(
                "src:mfds:other", "other", SourceRef.DataMode.LIVE, "Other record");
        SourceRef missingId = source(
                null, "missing-id", SourceRef.DataMode.LIVE, "Record with no source id");

        return Stream.of(
                Arguments.of(
                        "source missing",
                        context(List.of(), Map.of(IBUPROFEN_KO, ibuprofen))),
                Arguments.of(
                        "grounded record missing",
                        context(List.of(LIVE_IBUPROFEN), Map.of())),
                Arguments.of(
                        "duplicate source id",
                        context(
                                List.of(LIVE_IBUPROFEN, LIVE_IBUPROFEN),
                                Map.of(IBUPROFEN_KO, ibuprofen))),
                Arguments.of(
                        "source id missing",
                        context(
                                List.of(missingId),
                                Map.of(
                                        IBUPROFEN_KO,
                                        groundedFor(missingId, "Ibuprofen")))),
                Arguments.of(
                        "two products mapped to one source",
                        context(
                                List.of(LIVE_IBUPROFEN),
                                Map.of(
                                        IBUPROFEN_KO, ibuprofen,
                                        ACETAMINOPHEN_KO, ibuprofen))),
                Arguments.of(
                        "source and grounded record mismatch",
                        context(List.of(other), Map.of(IBUPROFEN_KO, ibuprofen))),
                Arguments.of(
                        "normalized ingredients disagree with server keys",
                        context(
                                List.of(LIVE_IBUPROFEN),
                                Map.of(
                                        IBUPROFEN_KO,
                                        grounded(
                                                LIVE_IBUPROFEN.id(),
                                                Set.of("acetaminophen"),
                                                AllergyCheck.noMatch(),
                                                "Ibuprofen Tablets",
                                                List.of("Ibuprofen"),
                                                null,
                                                null,
                                                MermAidAnswer.DrugCard.PrescriptionStatus.OTC,
                                                List.of(),
                                                null)))));
    }

    @Test
    @DisplayName("the existing validator must approve the completed candidate")
    void candidateIsPassedThroughTheExistingValidator() {
        AtomicReference<MermAidAnswer> candidate = new AtomicReference<>();
        AnswerValidator recordingValidator = new AnswerValidator(normalizer) {
            @Override
            public List<ViolationCode> validate(
                    MermAidAnswer answer, Map<String, GroundedDrug> groundedDrugs) {
                candidate.set(answer);
                return List.of();
            }
        };
        ServerAuthoredAnswerBuilder recordingBuilder =
                new ServerAuthoredAnswerBuilder(normalizer, recordingValidator);
        DrugContext context = context(
                List.of(LIVE_IBUPROFEN),
                Map.of(IBUPROFEN_KO, groundedFor(LIVE_IBUPROFEN, "Ibuprofen")));

        Optional<MermAidAnswer> built = recordingBuilder.build(context);

        assertThat(built).isPresent();
        assertThat(candidate.get()).isSameAs(built.orElseThrow());
    }

    @Test
    @DisplayName("a validator violation discards the entire server candidate")
    void validatorViolationFailsClosed() {
        AnswerValidator rejectingValidator = new AnswerValidator(normalizer) {
            @Override
            public List<ViolationCode> validate(
                    MermAidAnswer answer, Map<String, GroundedDrug> groundedDrugs) {
                return List.of(ViolationCode.INV6_PRODUCT_SOURCE_MISMATCH);
            }
        };
        ServerAuthoredAnswerBuilder rejectingBuilder =
                new ServerAuthoredAnswerBuilder(normalizer, rejectingValidator);
        DrugContext context = context(
                List.of(LIVE_IBUPROFEN),
                Map.of(IBUPROFEN_KO, groundedFor(LIVE_IBUPROFEN, "Ibuprofen")));

        assertThat(rejectingBuilder.build(context)).isEmpty();
    }

    private static DrugContext context(
            List<SourceRef> sources, Map<String, GroundedDrug> groundedDrugs) {
        return new DrugContext("DRUG_CONTEXT: model-only prose", groundedDrugs, sources);
    }

    private static GroundedDrug groundedFor(SourceRef source, String ingredient) {
        String key = ingredient.toLowerCase();
        return grounded(
                source.id(),
                Set.of(key),
                AllergyCheck.noMatch(),
                ingredient + " Tablets",
                List.of(ingredient),
                null,
                null,
                MermAidAnswer.DrugCard.PrescriptionStatus.OTC,
                List.of(),
                null);
    }

    private static GroundedDrug grounded(
            String sourceRefId,
            Set<String> ingredientKeys,
            AllergyCheck allergyCheck,
            String productNameEn,
            List<String> ingredientNamesEn,
            String officialDosageKo,
            String officialEfficacyKo,
            MermAidAnswer.DrugCard.PrescriptionStatus prescriptionStatus,
            List<String> warnings,
            String officialCautionKo) {
        return new GroundedDrug(
                sourceRefId,
                ingredientKeys,
                allergyCheck,
                productNameEn,
                ingredientNamesEn,
                officialDosageKo,
                officialEfficacyKo,
                prescriptionStatus,
                warnings,
                officialCautionKo);
    }

    private static SourceRef source(
            String id, String recordId, SourceRef.DataMode dataMode, String title) {
        return new SourceRef(
                id,
                "식품의약품안전처",
                recordId,
                Instant.parse("2026-07-15T12:00:00Z"),
                dataMode,
                title);
    }
}
