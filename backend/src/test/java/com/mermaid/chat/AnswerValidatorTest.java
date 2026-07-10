package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.mermaid.chat.dto.AllergyCheck;
import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.chat.dto.UiAction;
import com.mermaid.common.SourceRef;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * These are the checks a JSON Schema cannot make. Each one corresponds to a way a
 * schema-conformant answer can still be wrong — and, for a medical app, dangerous.
 */
class AnswerValidatorTest {

    private final AnswerValidator validator = new AnswerValidator();

    private static final Instant NOW = Instant.parse("2026-07-10T02:00:00Z");

    private static SourceRef source(String id) {
        return new SourceRef(id, "mfds", "200000001", NOW, SourceRef.DataMode.LIVE, "e약은요");
    }

    private static MermAidAnswer.DrugCard drug(String nameKo, String sourceRefId, AllergyCheck check) {
        return new MermAidAnswer.DrugCard(
                "drug:mfds:200000001",
                nameKo,
                null,
                List.of(),
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

        assertThat(validator.validate(a, Set.of("타이레놀"))).isEmpty();
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

            assertThat(validator.validate(a, Set.of("타이레놀")))
                    .anyMatch(v -> v.contains("never retrieved"));
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

            assertThat(validator.validate(a, Set.of())).isNotEmpty();
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

            assertThat(validator.validate(a, Set.of()))
                    .anyMatch(v -> v.contains("no SHOW_EMERGENCY_CALL"));
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

            assertThat(validator.validate(a, Set.of())).isEmpty();
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

        assertThat(validator.validate(a, Set.of("타이레놀")))
                .anyMatch(v -> v.contains("unknown source_ref_id"));
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

        assertThat(validator.validate(a, Set.of("타이레놀")))
                .anyMatch(v -> v.contains("names no ingredient"));
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

        assertThat(validator.validate(a, Set.of())).anyMatch(v -> v.contains("data_mode=fixture"));
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

        assertThat(validator.validate(withUrl, Set.of())).anyMatch(v -> v.contains("URL or markup"));
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

        assertThat(validator.validate(a, Set.of("타이레놀"))).anyMatch(v -> v.contains("URL or markup"));
    }
}
