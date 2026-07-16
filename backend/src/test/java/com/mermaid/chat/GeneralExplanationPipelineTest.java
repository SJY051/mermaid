package com.mermaid.chat;

import static com.mermaid.chat.ModelGeneralExplanationDraft.GeneralConcept.BACTERIA;
import static com.mermaid.chat.ModelGeneralExplanationDraft.GeneralConcept.BLOOD_CELL_FORMATION;
import static com.mermaid.chat.ModelGeneralExplanationDraft.GeneralConcept.BLOOD_GLUCOSE_REGULATION;
import static com.mermaid.chat.ModelGeneralExplanationDraft.GeneralConcept.COMMON_VIRAL_INFECTIONS;
import static com.mermaid.chat.ModelGeneralExplanationDraft.GeneralConcept.DIGESTIVE_SYSTEM;
import static com.mermaid.chat.ModelGeneralExplanationDraft.GeneralConcept.INFLAMMATION;
import static com.mermaid.chat.ModelGeneralExplanationDraft.GeneralConcept.INJURY;
import static com.mermaid.chat.ModelGeneralExplanationDraft.GeneralConcept.IRRITATION;
import static com.mermaid.chat.ModelGeneralExplanationDraft.GeneralConcept.OTHER_COMMON_INFECTIONS;
import static com.mermaid.chat.ModelGeneralExplanationDraft.GeneralConcept.VIRUSES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mermaid.chat.GeneralExplanationSemanticVerifier.ConfidenceBucket;
import com.mermaid.chat.GeneralExplanationSemanticVerifier.SemanticReviewDecision;
import com.mermaid.chat.GeneralExplanationSemanticVerifier.SemanticReviewInput;
import com.mermaid.chat.GeneralExplanationSemanticVerifier.SemanticViolation;
import com.mermaid.chat.GeneralExplanationSemanticVerifier.Verdict;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
                        ModelGeneralExplanationDraft.GeneralSubject.MILD_FEVER,
                        List.of(COMMON_VIRAL_INFECTIONS, OTHER_COMMON_INFECTIONS))));

        GeneralExplanationPipeline.Outcome outcome = pipeline.process(
                "I have a mild fever.",
                draft,
                Set.of(),
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE);

        assertThat(outcome.failure()).isNull();
        assertThat(outcome.explanation()).isPresent().get().satisfies(explanation -> assertThat(
                        explanation.summary())
                .isEqualTo("Mild fever can happen for many reasons. General possibilities include "
                        + "common viral infections and other common infections. Symptoms alone "
                        + "cannot identify the cause or confirm a diagnosis."));
    }

    @Test
    void rendersAnOrdinaryTermDefinitionFromAnAllowlistedPredicateAndConcepts() {
        GeneralExplanationPipeline pipeline = pipeline(ast -> ALLOW);
        ModelGeneralExplanationDraft draft = new ModelGeneralExplanationDraft(List.of(
                new ModelGeneralExplanationDraft.ModelTermDefinition(
                        ModelGeneralExplanationDraft.GeneralSubject.INFLAMMATION,
                        ModelGeneralExplanationDraft.DefinitionPredicate.BODY_RESPONSE_TO,
                        List.of(IRRITATION, INJURY))));

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
                        ModelGeneralExplanationDraft.GeneralSubject.VIRAL_INFECTION,
                        ModelGeneralExplanationDraft.GeneralSubject.BACTERIAL_INFECTION,
                        ModelGeneralExplanationDraft.ComparisonDimension.GENERAL_CAUSE_TYPE,
                        List.of(VIRUSES),
                        List.of(BACTERIA))));

        GeneralExplanationPipeline.Outcome outcome = pipeline.process(
                "What is the difference between a viral infection and a bacterial infection?",
                draft,
                Set.of(),
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE);

        assertThat(outcome.explanation()).isPresent().get().satisfies(explanation -> assertThat(
                        explanation.summary())
                .contains("Viral infection and bacterial infection differ in their general cause type")
                .contains("Viral infection is associated with viruses, while bacterial infection is associated with bacteria")
                .endsWith("This distinction cannot determine which applies to a person."));
    }

    @Test
    void semanticReviewReceivesOnlyNormalizedMentionsEnumsAndPredicates() {
        AtomicReference<SemanticReviewInput> reviewed = new AtomicReference<>();
        GeneralExplanationPipeline pipeline = pipeline(input -> {
            reviewed.set(input);
            return ALLOW;
        });

        pipeline.process(
                "I have a mild fever.",
                mildFeverDraft(),
                Set.of(),
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE);

        assertThat(reviewed.get().claims()).singleElement().satisfies(claim -> {
            assertThat(claim)
                    .isInstanceOf(GeneralExplanationSemanticVerifier.SymptomPossibilityReview.class);
            GeneralExplanationSemanticVerifier.SymptomPossibilityReview possibility =
                    (GeneralExplanationSemanticVerifier.SymptomPossibilityReview) claim;
            assertThat(possibility.subject())
                    .isEqualTo(ModelGeneralExplanationDraft.GeneralSubject.MILD_FEVER);
            assertThat(possibility.possibleCauses()).containsExactly(COMMON_VIRAL_INFECTIONS);
        });
    }

    @Test
    void modelMentionMustBeAnExactPhraseFromTheLatestUserTurn() {
        GeneralExplanationPipeline pipeline = pipeline(ast -> ALLOW);
        ModelGeneralExplanationDraft draft = new ModelGeneralExplanationDraft(List.of(
                new ModelGeneralExplanationDraft.ModelSymptomPossibility(
                        ModelGeneralExplanationDraft.GeneralSubject.INFLUENZA,
                        List.of(COMMON_VIRAL_INFECTIONS))));

        GeneralExplanationPipeline.Outcome outcome = pipeline.process(
                "I have a mild fever.",
                draft,
                Set.of(),
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE);

        assertShapeRejected(outcome);
    }

    @ParameterizedTest
    @MethodSource("ordinaryMentions")
    void ordinaryEnglishMedicalTermsMayContainDigitsApostrophesAndHyphens(
            ModelGeneralExplanationDraft.GeneralSubject subject,
            String userTurn,
            ModelGeneralExplanationDraft.DefinitionPredicate predicate,
            List<ModelGeneralExplanationDraft.GeneralConcept> concepts,
            String expectedSubject) {
        GeneralExplanationPipeline pipeline = pipeline(ast -> ALLOW);
        ModelGeneralExplanationDraft draft = new ModelGeneralExplanationDraft(List.of(
                new ModelGeneralExplanationDraft.ModelTermDefinition(
                        subject, predicate, concepts)));

        GeneralExplanationPipeline.Outcome outcome = pipeline.process(
                userTurn,
                draft,
                Set.of(),
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE);

        assertThat(outcome.explanation()).isPresent().get().satisfies(explanation ->
                assertThat(explanation.summary())
                        .startsWith(expectedSubject));
    }

    @ParameterizedTest
    @MethodSource("untrustedSubjectStrings")
    void freeTextCannotBecomeAClaimSubject(String candidate) throws NoSuchMethodException {
        var constructor = ModelGeneralExplanationDraft.ModelSymptomPossibility.class
                .getDeclaredConstructor(
                        ModelGeneralExplanationDraft.GeneralSubject.class, List.class);

        assertThatThrownBy(() -> constructor.newInstance(
                        candidate, List.of(COMMON_VIRAL_INFECTIONS)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void modelCannotSupplyFreeTextForAConceptSlot() throws NoSuchMethodException {
        assertThat(ModelGeneralExplanationDraft.ModelSymptomPossibility.class
                        .getRecordComponents()[0]
                        .getType())
                .isEqualTo(ModelGeneralExplanationDraft.GeneralSubject.class);
        assertThat(ModelGeneralExplanationDraft.ModelSymptomPossibility.class
                        .getRecordComponents()[1]
                        .getGenericType()
                        .getTypeName())
                .isEqualTo("java.util.List<com.mermaid.chat.ModelGeneralExplanationDraft$GeneralConcept>");
        assertThatThrownBy(() -> ModelGeneralExplanationDraft.ModelSymptomPossibility.class
                        .getDeclaredConstructor(
                                ModelGeneralExplanationDraft.GeneralSubject.class, List.class)
                        .newInstance(
                                ModelGeneralExplanationDraft.GeneralSubject.MILD_FEVER,
                                List.of("antibiotics treat pneumonia")))
                .hasRootCauseInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void incompatiblePredicateAndConceptCannotReachSemanticReview() {
        AtomicInteger reviews = new AtomicInteger();
        GeneralExplanationPipeline pipeline = pipeline(ast -> {
            reviews.incrementAndGet();
            return ALLOW;
        });
        ModelGeneralExplanationDraft draft = new ModelGeneralExplanationDraft(List.of(
                new ModelGeneralExplanationDraft.ModelTermDefinition(
                        ModelGeneralExplanationDraft.GeneralSubject.INFLUENZA,
                        ModelGeneralExplanationDraft.DefinitionPredicate.INFECTION_CAUSED_BY,
                        List.of(INJURY))));

        GeneralExplanationPipeline.Outcome outcome = pipeline.process(
                "What does influenza mean?",
                draft,
                Set.of(),
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE);

        assertThat(reviews).hasValue(0);
        assertShapeRejected(outcome);
    }

    @ParameterizedTest
    @MethodSource("invalidDefinitionRules")
    void subjectPredicateAndConceptMustMatchTheServerOwnedRule(
            ModelGeneralExplanationDraft.GeneralSubject subject,
            String userTurn,
            ModelGeneralExplanationDraft.DefinitionPredicate predicate,
            List<ModelGeneralExplanationDraft.GeneralConcept> concepts) {
        AtomicInteger reviews = new AtomicInteger();
        GeneralExplanationPipeline pipeline = pipeline(ast -> {
            reviews.incrementAndGet();
            return ALLOW;
        });
        ModelGeneralExplanationDraft draft = new ModelGeneralExplanationDraft(List.of(
                new ModelGeneralExplanationDraft.ModelTermDefinition(
                        subject, predicate, concepts)));

        GeneralExplanationPipeline.Outcome outcome = pipeline.process(
                userTurn,
                draft,
                Set.of(),
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE);

        assertThat(reviews).hasValue(0);
        assertShapeRejected(outcome);
    }

    @Test
    void comparisonMustMatchTheServerOwnedSubjectPairAndDimension() {
        GeneralExplanationPipeline pipeline = pipeline(ast -> ALLOW);
        ModelGeneralExplanationDraft draft = new ModelGeneralExplanationDraft(List.of(
                new ModelGeneralExplanationDraft.ModelGeneralComparison(
                        ModelGeneralExplanationDraft.GeneralSubject.VITAMIN_B12,
                        ModelGeneralExplanationDraft.GeneralSubject.COVID_19,
                        ModelGeneralExplanationDraft.ComparisonDimension.GENERAL_CAUSE_TYPE,
                        List.of(VIRUSES),
                        List.of(BACTERIA))));

        GeneralExplanationPipeline.Outcome outcome = pipeline.process(
                "What is the difference between B12 and COVID-19?",
                draft,
                Set.of(),
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE);

        assertShapeRejected(outcome);
    }

    @Test
    void symptomPossibilitiesUseTheServerOwnedConceptsForThatSubject() {
        GeneralExplanationPipeline pipeline = pipeline(ast -> ALLOW);
        ModelGeneralExplanationDraft draft = new ModelGeneralExplanationDraft(List.of(
                new ModelGeneralExplanationDraft.ModelSymptomPossibility(
                        ModelGeneralExplanationDraft.GeneralSubject.COUGH,
                        List.of(INJURY))));

        GeneralExplanationPipeline.Outcome outcome = pipeline.process(
                "I have a cough.",
                draft,
                Set.of(),
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE);

        assertShapeRejected(outcome);
    }

    @Test
    void aServerSubjectIsRejectedWhenTheCurrentTurnBoundItAsAMedicineTerm() {
        GeneralExplanationPipeline pipeline = pipeline(ast -> ALLOW);
        ModelGeneralExplanationDraft draft = new ModelGeneralExplanationDraft(List.of(
                new ModelGeneralExplanationDraft.ModelTermDefinition(
                        ModelGeneralExplanationDraft.GeneralSubject.VITAMIN_B12,
                        ModelGeneralExplanationDraft.DefinitionPredicate.NUTRIENT_RELATED_TO,
                        List.of(BLOOD_CELL_FORMATION))));

        GeneralExplanationPipeline.Outcome outcome = pipeline.process(
                "What is B12?",
                draft,
                Set.of("B12"),
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE);

        assertShapeRejected(outcome);
    }

    @ParameterizedTest
    @MethodSource("nonRenderingSemanticDecisions")
    void semanticReviewMustAllowWithHighConfidenceAndNoViolations(
            SemanticReviewDecision decision) {
        AtomicInteger reviews = new AtomicInteger();
        GeneralExplanationPipeline pipeline = pipeline(ast -> {
            reviews.incrementAndGet();
            return decision;
        });

        GeneralExplanationPipeline.Outcome outcome = pipeline.process(
                "I have a mild fever.",
                mildFeverDraft(),
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
            throw new GeneralExplanationSemanticVerifier.SemanticReviewUnavailableException();
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
    void internalVerifierBugIsNotMisreportedAsProviderUnavailability() {
        GeneralExplanationPipeline pipeline = pipeline(ast -> {
            throw new IllegalStateException("internal bug");
        });

        assertThatThrownBy(() -> pipeline.process(
                        "I have a mild fever.",
                        mildFeverDraft(),
                        Set.of(),
                        ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("internal bug");
    }

    @Test
    void rendererRefusesProtectedModesBeforeSemanticReview() {
        AtomicInteger reviews = new AtomicInteger();
        GeneralExplanationPipeline pipeline = pipeline(ast -> {
            reviews.incrementAndGet();
            return ALLOW;
        });

        GeneralExplanationPipeline.Outcome outcome = pipeline.process(
                "I have a mild fever.",
                mildFeverDraft(),
                Set.of(),
                ResponsePlan.ResponseMode.T3_REFUSE_CLINICAL_AUTHORITY);

        assertThat(reviews).hasValue(0);
        assertThat(outcome.explanation()).isEmpty();
        assertThat(outcome.failure())
                .isEqualTo(GeneralExplanationPipeline.Failure.MODE_NOT_RENDERABLE);
    }

    @Test
    void renderedCapabilityCannotBeConstructedOutsideThePipeline() {
        assertThat(GeneralExplanationPipeline.RenderedGeneralExplanation.class
                        .getDeclaredConstructors())
                .allSatisfy(constructor -> assertThat(Modifier.isPrivate(constructor.getModifiers()))
                        .isTrue());
    }

    private static GeneralExplanationPipeline pipeline(
            GeneralExplanationSemanticVerifier verifier) {
        return new GeneralExplanationPipeline(verifier);
    }

    private static ModelGeneralExplanationDraft mildFeverDraft() {
        return new ModelGeneralExplanationDraft(List.of(
                new ModelGeneralExplanationDraft.ModelSymptomPossibility(
                        ModelGeneralExplanationDraft.GeneralSubject.MILD_FEVER,
                        List.of(COMMON_VIRAL_INFECTIONS))));
    }

    private static void assertShapeRejected(GeneralExplanationPipeline.Outcome outcome) {
        assertThat(outcome.explanation()).isEmpty();
        assertThat(outcome.failure())
                .isEqualTo(GeneralExplanationPipeline.Failure.SHAPE_REJECTED);
    }

    private static Stream<Arguments> ordinaryMentions() {
        return Stream.of(
                Arguments.of(
                        ModelGeneralExplanationDraft.GeneralSubject.CROHNS_DISEASE,
                        "What does Crohn's disease mean?",
                        ModelGeneralExplanationDraft.DefinitionPredicate.BODY_PROCESS_INVOLVING,
                        List.of(INFLAMMATION, DIGESTIVE_SYSTEM),
                        "Crohn's disease"),
                Arguments.of(
                        ModelGeneralExplanationDraft.GeneralSubject.TYPE_2_DIABETES,
                        "What is type 2 diabetes?",
                        ModelGeneralExplanationDraft.DefinitionPredicate.BODY_PROCESS_INVOLVING,
                        List.of(BLOOD_GLUCOSE_REGULATION),
                        "Type 2 diabetes"),
                Arguments.of(
                        ModelGeneralExplanationDraft.GeneralSubject.COVID_19,
                        "What does COVID-19 mean?",
                        ModelGeneralExplanationDraft.DefinitionPredicate.INFECTION_CAUSED_BY,
                        List.of(VIRUSES),
                        "COVID-19"),
                Arguments.of(
                        ModelGeneralExplanationDraft.GeneralSubject.VITAMIN_B12,
                        "What is B12?",
                        ModelGeneralExplanationDraft.DefinitionPredicate.NUTRIENT_RELATED_TO,
                        List.of(BLOOD_CELL_FORMATION),
                        "B12"));
    }

    private static Stream<Arguments> untrustedSubjectStrings() {
        return Stream.of(
                Arguments.of("influenza. You have pneumonia"),
                Arguments.of("you have pneumonia"),
                Arguments.of("safe for everyone"),
                Arguments.of("antibiotics treat pneumonia"),
                Arguments.of("aspirin prevents strokes"),
                Arguments.of("fever beyond seventy two hrs"),
                Arguments.of("pneumonia requires hospitalization"),
                Arguments.of("probably influenza"),
                Arguments.of("sаfe for everyone"),
                Arguments.of("dоse"),
                Arguments.of("아세트아미노펜 오백 밀리그램 매일"),
                Arguments.of("you\u200B have influenza"),
                Arguments.of("line\nfeed"),
                Arguments.of("javascript alert"),
                Arguments.of("amoxicillin"),
                Arguments.of("penicillin"),
                Arguments.of("morphine"),
                Arguments.of("fentanyl"),
                Arguments.of("oxycodone"),
                Arguments.of("tramadol"),
                Arguments.of("metformin"),
                Arguments.of("warfarin"),
                Arguments.of("take-two tramadol"),
                Arguments.of("dose200mg"));
    }

    private static Stream<Arguments> invalidDefinitionRules() {
        return Stream.of(
                Arguments.of(
                        ModelGeneralExplanationDraft.GeneralSubject.VITAMIN_B12,
                        "What is B12?",
                        ModelGeneralExplanationDraft.DefinitionPredicate.INFECTION_CAUSED_BY,
                        List.of(VIRUSES)),
                Arguments.of(
                        ModelGeneralExplanationDraft.GeneralSubject.COVID_19,
                        "What is COVID-19?",
                        ModelGeneralExplanationDraft.DefinitionPredicate.NUTRIENT_RELATED_TO,
                        List.of(BLOOD_CELL_FORMATION)),
                Arguments.of(
                        ModelGeneralExplanationDraft.GeneralSubject.CROHNS_DISEASE,
                        "What is Crohn's disease?",
                        ModelGeneralExplanationDraft.DefinitionPredicate.MEASUREMENT_OF,
                        List.of(ModelGeneralExplanationDraft.GeneralConcept.BODY_TEMPERATURE)),
                Arguments.of(
                        ModelGeneralExplanationDraft.GeneralSubject.INFLAMMATION,
                        "What is inflammation?",
                        ModelGeneralExplanationDraft.DefinitionPredicate.INFECTION_CAUSED_BY,
                        List.of(BACTERIA)),
                Arguments.of(
                        ModelGeneralExplanationDraft.GeneralSubject.VIRAL_INFECTION,
                        "What is a viral infection?",
                        ModelGeneralExplanationDraft.DefinitionPredicate.BODY_RESPONSE_TO,
                        List.of(INJURY)),
                Arguments.of(
                        ModelGeneralExplanationDraft.GeneralSubject.INFECTION,
                        "What is an infection?",
                        ModelGeneralExplanationDraft.DefinitionPredicate.INFECTION_CAUSED_BY,
                        List.of(VIRUSES)),
                Arguments.of(
                        ModelGeneralExplanationDraft.GeneralSubject.INFECTION,
                        "What is an infection?",
                        ModelGeneralExplanationDraft.DefinitionPredicate.INFECTION_CAUSED_BY,
                        List.of(BACTERIA)));
    }

    private static Stream<SemanticReviewDecision> nonRenderingSemanticDecisions() {
        return Stream.of(
                new SemanticReviewDecision(
                        Verdict.ALLOW,
                        Set.of(SemanticViolation.FACTUALLY_IMPLAUSIBLE),
                        ConfidenceBucket.HIGH),
                new SemanticReviewDecision(Verdict.ALLOW, Set.of(), ConfidenceBucket.MEDIUM),
                new SemanticReviewDecision(Verdict.ALLOW, Set.of(), ConfidenceBucket.LOW),
                new SemanticReviewDecision(Verdict.REJECT, Set.of(), ConfidenceBucket.HIGH));
    }
}
