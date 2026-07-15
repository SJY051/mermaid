package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.mermaid.chat.AnswerValidator.ViolationCode;
import com.mermaid.chat.DrugContextRetriever.GroundedDrug;
import com.mermaid.chat.dto.AllergyCheck;
import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.chat.dto.UiAction;
import com.mermaid.common.SourceRef;
import com.mermaid.drug.IngredientNormalizer;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * These are the checks a JSON Schema cannot make. Each one corresponds to a way a
 * schema-conformant answer can still be wrong — and, for a medical app, dangerous.
 */
class AnswerValidatorTest {

    private final AnswerValidator validator = new AnswerValidator(new IngredientNormalizer());

    private static final Instant NOW = Instant.parse("2026-07-10T02:00:00Z");

    private static SourceRef source(String id) {
        return new SourceRef(id, "mfds", "200000001", NOW, SourceRef.DataMode.LIVE, "e약은요");
    }

    private static Map<String, GroundedDrug> grounded(
            String productNameKo, String sourceRefId, String... ingredientKeys) {
        return Map.of(
                productNameKo,
                new GroundedDrug(
                        sourceRefId,
                        Set.of(ingredientKeys),
                        AllergyCheck.noMatch(),
                        null,
                        List.of(),
                        null,
                        null,
                        MermAidAnswer.DrugCard.PrescriptionStatus.OTC,
                        List.of(),
                        null));
    }

    private static MermAidAnswer.Ingredient ingredient(String nameEn) {
        return new MermAidAnswer.Ingredient(null, nameEn, null, null, null);
    }

    private static MermAidAnswer.DrugCard drug(String nameKo, String sourceRefId, AllergyCheck check) {
        return drug(nameKo, sourceRefId, check, List.of());
    }

    private static MermAidAnswer.DrugCard drug(
            String nameKo,
            String sourceRefId,
            AllergyCheck check,
            List<MermAidAnswer.Ingredient> ingredients) {
        return new MermAidAnswer.DrugCard(
                "drug:mfds:200000001",
                nameKo,
                null,
                ingredients,
                null,
                null,
                null,
                List.of(),
                MermAidAnswer.DrugCard.PrescriptionStatus.OTC,
                check,
                sourceRefId);
    }

    private static MermAidAnswer answer(
            MermAidAnswer.Urgency.Level level,
            List<MermAidAnswer.DrugCard> drugs,
            List<UiAction> actions,
            List<SourceRef> sources,
            MermAidAnswer.DataStatus dataStatus) {
        return new MermAidAnswer(
                "1.0",
                "a1",
                "en",
                dataStatus,
                new MermAidAnswer.Urgency(level, "t", "m", List.of(), List.of()),
                "summary",
                List.of(),
                List.of(),
                drugs,
                actions,
                sources,
                List.of(),
                "disclaimer");
    }

    @Test
    @DisplayName("a clean answer passes")
    void cleanAnswerPasses() {
        MermAidAnswer a =
                answer(
                        MermAidAnswer.Urgency.Level.ROUTINE,
                        List.of(drug("타이레놀", "src:1", AllergyCheck.noMatch())),
                        List.of(),
                        List.of(source("src:1")),
                        MermAidAnswer.DataStatus.LIVE);

        assertThat(validator.validate(a, grounded("타이레놀", "src:1"))).isEmpty();
    }

    @Nested
    @DisplayName("invariant 6 — the hallucination gate")
    class HallucinationGate {

        @Test
        @DisplayName("a drug we never retrieved is rejected, even with valid JSON")
        void rejectsInventedDrug() {
            // The model produced a structurally perfect card for a product that does not exist.
            // This is the failure the whole validator exists for.
            MermAidAnswer a =
                    answer(
                            MermAidAnswer.Urgency.Level.ROUTINE,
                            List.of(drug("존재하지않는약", "src:1", AllergyCheck.noMatch())),
                            List.of(),
                            List.of(source("src:1")),
                            MermAidAnswer.DataStatus.LIVE);

            assertThat(validator.validate(a, grounded("타이레놀", "src:1")))
                    .containsExactly(ViolationCode.INV6_PRODUCT_NOT_RETRIEVED);
        }

        @Test
        @DisplayName("when we retrieved nothing, the answer may name no drug at all")
        void noRetrievalMeansNoDrugs() {
            MermAidAnswer a =
                    answer(
                            MermAidAnswer.Urgency.Level.ROUTINE,
                            List.of(drug("타이레놀", "src:1", AllergyCheck.noMatch())),
                            List.of(),
                            List.of(source("src:1")),
                            MermAidAnswer.DataStatus.LIVE);

            assertThat(validator.validate(a, Map.of()))
                    .containsExactly(ViolationCode.INV6_PRODUCT_NOT_RETRIEVED);
        }

        @Test
        @DisplayName("ingredient identity is set equality, not list order")
        void ingredientOrderDoesNotMatter() {
            MermAidAnswer a =
                    answer(
                            MermAidAnswer.Urgency.Level.ROUTINE,
                            List.of(drug(
                                    "타이레놀",
                                    "src:1",
                                    AllergyCheck.noMatch(),
                                    List.of(ingredient("Ibuprofen"), ingredient("Acetaminophen")))),
                            List.of(),
                            List.of(source("src:1")),
                            MermAidAnswer.DataStatus.LIVE);

            assertThat(validator.validate(
                            a, grounded("타이레놀", "src:1", "acetaminophen", "ibuprofen")))
                    .isEmpty();
        }

        @Test
        @DisplayName("a card missing one retrieved ingredient is rejected")
        void rejectsMissingIngredient() {
            MermAidAnswer a =
                    answer(
                            MermAidAnswer.Urgency.Level.ROUTINE,
                            List.of(drug(
                                    "타이레놀",
                                    "src:1",
                                    AllergyCheck.noMatch(),
                                    List.of(ingredient("Acetaminophen")))),
                            List.of(),
                            List.of(source("src:1")),
                            MermAidAnswer.DataStatus.LIVE);

            assertThat(validator.validate(
                            a, grounded("타이레놀", "src:1", "acetaminophen", "ibuprofen")))
                    .containsExactly(ViolationCode.INV6_INGREDIENT_MISMATCH);
        }

        @Test
        @DisplayName("a card adding one fabricated ingredient is rejected")
        void rejectsExtraIngredient() {
            MermAidAnswer a =
                    answer(
                            MermAidAnswer.Urgency.Level.ROUTINE,
                            List.of(drug(
                                    "타이레놀",
                                    "src:1",
                                    AllergyCheck.noMatch(),
                                    List.of(ingredient("Acetaminophen"), ingredient("Caffeine")))),
                            List.of(),
                            List.of(source("src:1")),
                            MermAidAnswer.DataStatus.LIVE);

            assertThat(validator.validate(a, grounded("타이레놀", "src:1", "acetaminophen")))
                    .containsExactly(ViolationCode.INV6_INGREDIENT_MISMATCH);
        }

        @Test
        @DisplayName("parenthesised extra ingredients cannot collapse onto a retrieved identity")
        void rejectsParenthesisedIngredientSmuggling() {
            MermAidAnswer a =
                    answer(
                            MermAidAnswer.Urgency.Level.ROUTINE,
                            List.of(drug(
                                    "타이레놀",
                                    "src:1",
                                    AllergyCheck.noMatch(),
                                    List.of(ingredient("Acetaminophen (Caffeine)")))),
                            List.of(),
                            List.of(source("src:1")),
                            MermAidAnswer.DataStatus.LIVE);

            assertThat(validator.validate(a, grounded("타이레놀", "src:1", "acetaminophen")))
                    .containsExactly(ViolationCode.INV6_INGREDIENT_MISMATCH);
        }
    }

    @Nested
    @DisplayName("invariant 4 — an emergency always offers the call")
    class EmergencyCall {

        @Test
        @DisplayName("emergency without SHOW_EMERGENCY_CALL is rejected")
        void emergencyNeedsCallAction() {
            MermAidAnswer a =
                    answer(
                            MermAidAnswer.Urgency.Level.EMERGENCY,
                            List.of(),
                            List.of(),
                            List.of(),
                            MermAidAnswer.DataStatus.UNAVAILABLE);

            assertThat(validator.validate(a, Map.of()))
                    .containsExactly(ViolationCode.INV4_EMERGENCY_ACTION_MISSING);
        }

        @Test
        @DisplayName("emergency with the call action passes")
        void emergencyWithCallActionPasses() {
            MermAidAnswer a =
                    answer(
                            MermAidAnswer.Urgency.Level.EMERGENCY,
                            List.of(),
                            List.of(UiAction.ShowEmergencyCall.korea119()),
                            List.of(),
                            MermAidAnswer.DataStatus.UNAVAILABLE);

            assertThat(validator.validate(a, Map.of())).isEmpty();
        }
    }

    @Test
    @DisplayName("invariant 1 — a drug citing a source we do not have is rejected")
    void danglingSourceRef() {
        MermAidAnswer a =
                answer(
                        MermAidAnswer.Urgency.Level.ROUTINE,
                        List.of(drug("타이레놀", "src:missing", AllergyCheck.noMatch())),
                        List.of(),
                        List.of(source("src:1")),
                        MermAidAnswer.DataStatus.LIVE);

        assertThat(validator.validate(a, grounded("타이레놀", "src:1")))
                .containsExactly(ViolationCode.INV1_UNKNOWN_DRUG_SOURCE);
    }

    @Test
    @DisplayName("invariant 2 — 'blocked' must name the ingredient it matched")
    void blockedWithoutIngredient() {
        AllergyCheck bad = new AllergyCheck(AllergyCheck.Status.BLOCKED, List.of(), "blocked");
        MermAidAnswer a =
                answer(
                        MermAidAnswer.Urgency.Level.ROUTINE,
                        List.of(drug("타이레놀", "src:1", bad)),
                        List.of(),
                        List.of(source("src:1")),
                        MermAidAnswer.DataStatus.LIVE);

        assertThat(validator.validate(a, grounded("타이레놀", "src:1")))
                .containsExactly(ViolationCode.INV2_BLOCKED_WITHOUT_MATCH);
    }

    @Test
    @DisplayName("invariant 3 — a 'live' answer cannot rest on fixture data")
    void liveCannotUseFixtures() {
        SourceRef fixture =
                new SourceRef("src:1", "mfds", "1", NOW, SourceRef.DataMode.FIXTURE, "sample");
        MermAidAnswer a =
                answer(
                        MermAidAnswer.Urgency.Level.ROUTINE,
                        List.of(),
                        List.of(),
                        List.of(fixture),
                        MermAidAnswer.DataStatus.LIVE);

        assertThat(validator.validate(a, Map.of()))
                .containsExactly(ViolationCode.INV3_LIVE_WITH_FIXTURE_SOURCE);
    }

    @Test
    @DisplayName("OUT-05 — every guidance citation is checked, regardless of evidence label")
    void everyGuidanceCitationMustBeServerOwned() {
        MermAidAnswer a =
                new MermAidAnswer(
                        "1.0",
                        "a1",
                        "en",
                        MermAidAnswer.DataStatus.LIVE,
                        new MermAidAnswer.Urgency(
                                MermAidAnswer.Urgency.Level.ROUTINE,
                                "t",
                                "m",
                                List.of(),
                                List.of()),
                        "summary",
                        List.of(),
                        List.of(new MermAidAnswer.Guidance(
                                "g1",
                                "General guidance",
                                "Ask a pharmacist.",
                                MermAidAnswer.Guidance.Evidence.GENERAL_SAFETY,
                                List.of("src:1", "src:fabricated"))),
                        List.of(),
                        List.of(),
                        List.of(source("src:1")),
                        List.of(),
                        "disclaimer");

        assertThat(validator.validate(a, Map.of()))
                .containsExactly(ViolationCode.INV5_GUIDANCE_SOURCE_UNKNOWN);
    }

    @Test
    @DisplayName("invariant 7 — no model-supplied URLs or markup reach the UI")
    void rejectsUrlsAndMarkup() {
        MermAidAnswer withUrl =
                new MermAidAnswer(
                        "1.0",
                        "a1",
                        "en",
                        MermAidAnswer.DataStatus.UNAVAILABLE,
                        new MermAidAnswer.Urgency(
                                MermAidAnswer.Urgency.Level.ROUTINE, "t", "m", List.of(), List.of()),
                        "Read more at https://evil.example",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        "disclaimer");

        assertThat(validator.validate(withUrl, Map.of()))
                .containsExactly(ViolationCode.INV7_FORBIDDEN_MARKUP);
    }

    @Test
    @DisplayName("invariant 7 — a script tag in a drug warning is caught too")
    void rejectsScriptInDrugWarning() {
        MermAidAnswer.DrugCard nasty =
                new MermAidAnswer.DrugCard(
                        "drug:mfds:1",
                        "타이레놀",
                        null,
                        List.of(),
                        null,
                        null,
                        null,
                        List.of("<script>alert(1)</script>"),
                        MermAidAnswer.DrugCard.PrescriptionStatus.OTC,
                        AllergyCheck.noMatch(),
                        "src:1");

        MermAidAnswer a =
                answer(
                        MermAidAnswer.Urgency.Level.ROUTINE,
                        List.of(nasty),
                        List.of(),
                        List.of(source("src:1")),
                        MermAidAnswer.DataStatus.LIVE);

        assertThat(validator.validate(a, grounded("타이레놀", "src:1")))
                .containsExactly(ViolationCode.INV7_FORBIDDEN_MARKUP);
    }

    @Test
    @DisplayName("invariant 7 — the rendered Korean product name is scanned too")
    void rejectsUrlInProductNameKo() {
        MermAidAnswer.DrugCard card =
                drug("https://evil.example", "src:1", AllergyCheck.noMatch());
        MermAidAnswer a =
                answer(
                        MermAidAnswer.Urgency.Level.ROUTINE,
                        List.of(card),
                        List.of(),
                        List.of(source("src:1")),
                        MermAidAnswer.DataStatus.LIVE);

        assertThat(validator.validate(a, grounded("https://evil.example", "src:1")))
                .containsExactly(ViolationCode.INV7_FORBIDDEN_MARKUP);
    }

    @Test
    @DisplayName("invariant 7 — ordinary comparison symbols are not mistaken for HTML")
    void allowsPlainComparisonSymbols() {
        MermAidAnswer a =
                new MermAidAnswer(
                        "1.0",
                        "a1",
                        "en",
                        MermAidAnswer.DataStatus.UNAVAILABLE,
                        new MermAidAnswer.Urgency(
                                MermAidAnswer.Urgency.Level.ROUTINE,
                                "Routine care",
                                "Ask a pharmacist.",
                                List.of(),
                                List.of()),
                        "A temperature below 38 C is less than 38 C: 37 < 38.",
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        List.of(),
                        "disclaimer");

        assertThat(validator.validate(a, Map.of())).isEmpty();
    }
}
