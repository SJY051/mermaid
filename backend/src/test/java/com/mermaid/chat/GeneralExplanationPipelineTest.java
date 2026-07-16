package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.mermaid.chat.GeneralExplanationSemanticVerifier.ConfidenceBucket;
import com.mermaid.chat.GeneralExplanationSemanticVerifier.SemanticReviewDecision;
import com.mermaid.chat.GeneralExplanationSemanticVerifier.SemanticViolation;
import com.mermaid.chat.GeneralExplanationSemanticVerifier.Verdict;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GeneralExplanationPipelineTest {

    private static final SemanticReviewDecision ALLOW = new SemanticReviewDecision(
            Verdict.ALLOW, Set.of(), ConfidenceBucket.HIGH);

    @Test
    void rendersACommonSymptomPossibilityWithAServerOwnedDiagnosticLimitation() {
        GeneralExplanationPipeline pipeline = pipeline(ast -> ALLOW);
        ModelGeneralExplanationDraft draft = new ModelGeneralExplanationDraft(List.of(
                new ModelGeneralExplanationDraft.ModelSymptomPossibility(
                        "mild fever", List.of("common viral infections", "irritation"))));

        GeneralExplanationPipeline.Outcome outcome = pipeline.process(
                "I have a mild fever.",
                draft,
                Set.of(),
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE);

        assertThat(outcome.failure()).isNull();
        assertThat(outcome.explanation()).isPresent().get().satisfies(explanation -> assertThat(
                        explanation.summary())
                .isEqualTo("Mild fever can happen for many reasons. General possibilities include "
                        + "common viral infections and irritation. Symptoms alone cannot identify "
                        + "the cause or confirm a diagnosis."));
    }

    @Test
    void rendersAnOrdinaryTermDefinitionFromAnAllowlistedPredicate() {
        GeneralExplanationPipeline pipeline = pipeline(ast -> ALLOW);
        ModelGeneralExplanationDraft draft = new ModelGeneralExplanationDraft(List.of(
                new ModelGeneralExplanationDraft.ModelTermDefinition(
                        "inflammation",
                        ModelGeneralExplanationDraft.DefinitionPredicate.BODY_RESPONSE_TO,
                        List.of("irritation", "injury"))));

        GeneralExplanationPipeline.Outcome outcome = pipeline.process(
                "What does inflammation mean?",
                draft,
                Set.of(),
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE);

        assertThat(outcome.explanation()).isPresent().get().satisfies(explanation -> assertThat(
                        explanation.summary())
                .isEqualTo("Inflammation is a medical term for a body response to irritation and "
                        + "injury. This is a general definition, not a diagnosis."));
    }

    @Test
    void rendersAGeneralComparisonWithoutSelectingOneForTheUser() {
        GeneralExplanationPipeline pipeline = pipeline(ast -> ALLOW);
        ModelGeneralExplanationDraft draft = new ModelGeneralExplanationDraft(List.of(
                new ModelGeneralExplanationDraft.ModelGeneralComparison(
                        "viral infection",
                        "bacterial infection",
                        ModelGeneralExplanationDraft.ComparisonDimension.GENERAL_CAUSE_TYPE,
                        List.of("viruses"),
                        List.of("bacteria"))));

        GeneralExplanationPipeline.Outcome outcome = pipeline.process(
                "What is the difference between a viral infection and a bacterial infection?",
                draft,
                Set.of(),
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE);

        assertThat(outcome.explanation()).isPresent().get().satisfies(explanation -> assertThat(
                        explanation.summary())
                .contains("differ in their general cause type")
                .endsWith("This distinction cannot determine which applies to a person."));
    }

    @Test
    void modelMentionMustBeAnExactPhraseFromTheLatestUserTurn() {
        GeneralExplanationPipeline pipeline = pipeline(ast -> ALLOW);
        ModelGeneralExplanationDraft draft = new ModelGeneralExplanationDraft(List.of(
                new ModelGeneralExplanationDraft.ModelSymptomPossibility(
                        "influenza", List.of("viral infections"))));

        GeneralExplanationPipeline.Outcome outcome = pipeline.process(
                "I have a mild fever.",
                draft,
                Set.of(),
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE);

        assertThat(outcome.explanation()).isEmpty();
        assertThat(outcome.failure())
                .isEqualTo(GeneralExplanationPipeline.Failure.SHAPE_REJECTED);
    }

    @ParameterizedTest
    @MethodSource("unsafeFragments")
    void fragmentShapeRejectsSentenceSmugglingAndExcludedLaunchCategories(String fragment) {
        GeneralExplanationPipeline pipeline = pipeline(ast -> ALLOW);
        ModelGeneralExplanationDraft draft = new ModelGeneralExplanationDraft(List.of(
                new ModelGeneralExplanationDraft.ModelSymptomPossibility(
                        "mild fever", List.of(fragment))));

        GeneralExplanationPipeline.Outcome outcome = pipeline.process(
                "I have a mild fever.",
                draft,
                Set.of(),
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE);

        assertThat(outcome.explanation()).isEmpty();
        assertThat(outcome.failure())
                .isEqualTo(GeneralExplanationPipeline.Failure.SHAPE_REJECTED);
    }

    @Test
    void currentMedicineTermsCannotEnterAGeneralConceptSlot() {
        GeneralExplanationPipeline pipeline = pipeline(ast -> ALLOW);
        ModelGeneralExplanationDraft draft = new ModelGeneralExplanationDraft(List.of(
                new ModelGeneralExplanationDraft.ModelSymptomPossibility(
                        "mild fever", List.of("acetaminophen"))));

        GeneralExplanationPipeline.Outcome outcome = pipeline.process(
                "I have a mild fever.",
                draft,
                Set.of("Acetaminophen"),
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE);

        assertThat(outcome.explanation()).isEmpty();
        assertThat(outcome.failure())
                .isEqualTo(GeneralExplanationPipeline.Failure.SHAPE_REJECTED);
    }

    @Test
    void semanticReviewMustAllowWithHighConfidenceAndNoViolations() {
        AtomicInteger reviews = new AtomicInteger();
        GeneralExplanationPipeline pipeline = pipeline(ast -> {
            reviews.incrementAndGet();
            return new SemanticReviewDecision(
                    Verdict.REJECT,
                    Set.of(SemanticViolation.FACTUALLY_IMPLAUSIBLE),
                    ConfidenceBucket.HIGH);
        });
        ModelGeneralExplanationDraft draft = mildFeverDraft();

        GeneralExplanationPipeline.Outcome outcome = pipeline.process(
                "I have a mild fever.",
                draft,
                Set.of(),
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE);

        assertThat(reviews).hasValue(1);
        assertThat(outcome.explanation()).isEmpty();
        assertThat(outcome.failure())
                .isEqualTo(GeneralExplanationPipeline.Failure.SEMANTIC_REJECTED);
    }

    @Test
    void semanticReviewFailureCannotBecomeAnExplanation() {
        GeneralExplanationPipeline pipeline = pipeline(ast -> {
            throw new IllegalStateException("provider text must not escape");
        });

        GeneralExplanationPipeline.Outcome outcome = pipeline.process(
                "I have a mild fever.",
                mildFeverDraft(),
                Set.of(),
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE);

        assertThat(outcome.explanation()).isEmpty();
        assertThat(outcome.failure())
                .isEqualTo(GeneralExplanationPipeline.Failure.SEMANTIC_UNAVAILABLE);
    }

    @Test
    void rendererRefusesProtectedModesEvenAfterAHighConfidenceReview() {
        GeneralExplanationPipeline pipeline = pipeline(ast -> ALLOW);

        GeneralExplanationPipeline.Outcome outcome = pipeline.process(
                "I have a mild fever.",
                mildFeverDraft(),
                Set.of(),
                ResponsePlan.ResponseMode.T3_REFUSE_CLINICAL_AUTHORITY);

        assertThat(outcome.explanation()).isEmpty();
        assertThat(outcome.failure())
                .isEqualTo(GeneralExplanationPipeline.Failure.MODE_NOT_RENDERABLE);
    }

    private static GeneralExplanationPipeline pipeline(
            GeneralExplanationSemanticVerifier verifier) {
        return new GeneralExplanationPipeline(
                new GeneralExplanationAdmission(), verifier, new GeneralExplanationRenderer());
    }

    private static ModelGeneralExplanationDraft mildFeverDraft() {
        return new ModelGeneralExplanationDraft(List.of(
                new ModelGeneralExplanationDraft.ModelSymptomPossibility(
                        "mild fever", List.of("common viral infections"))));
    }

    private static Stream<Arguments> unsafeFragments() {
        return Stream.of(
                Arguments.of("influenza. You have pneumonia"),
                Arguments.of("take two tablets"),
                Arguments.of("safe for everyone"),
                Arguments.of("government verified"),
                Arguments.of("after three days"),
                Arguments.of("call 119 immediately"),
                Arguments.of("personal risk score"),
                Arguments.of("disease specific treatment"),
                Arguments.of("you\u200B have influenza"),
                Arguments.of("line\nfeed"),
                Arguments.of("javascript alert"));
    }
}
