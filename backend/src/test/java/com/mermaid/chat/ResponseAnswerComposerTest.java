package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mermaid.chat.DrugContextRetriever.DrugContext;
import com.mermaid.chat.GeneralExplanationPipeline.RenderedGeneralExplanation;
import com.mermaid.chat.GeneralExplanationSemanticVerifier.ConfidenceBucket;
import com.mermaid.chat.GeneralExplanationSemanticVerifier.SemanticReviewDecision;
import com.mermaid.chat.GeneralExplanationSemanticVerifier.Verdict;
import com.mermaid.chat.ModelGeneralExplanationDraft.GeneralConcept;
import com.mermaid.chat.ModelGeneralExplanationDraft.GeneralSubject;
import com.mermaid.chat.ModelGeneralExplanationDraft.ModelSymptomPossibility;
import com.mermaid.chat.ResponseAnswerComposer.CapabilityFailure;
import com.mermaid.chat.ResponseAnswerComposer.CapabilityResults;
import com.mermaid.chat.dto.AllergyCheck;
import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.chat.dto.UiAction;
import com.mermaid.common.SourceRef;
import com.mermaid.facility.domain.FacilityOperationPreference;
import com.mermaid.facility.domain.FacilityType;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ResponseAnswerComposerTest {

    private final ServerAuthoredAnswerBuilder medicineBuilder = mock(ServerAuthoredAnswerBuilder.class);
    private final ResponseAnswerComposer composer =
            new ResponseAnswerComposer(medicineBuilder, new EmergencyTriage());

    @Test
    void capabilityResultsCannotCarryRawModelProseOrDirectWholeAnswer() {
        assertThat(CapabilityResults.class.getRecordComponents())
                .extracting(component -> component.getType().getName())
                .doesNotContain(String.class.getName(), MermAidAnswer.class.getName())
                .contains(RenderedGeneralExplanation.class.getName(), DrugContext.class.getName());
        assertThatThrownBy(() -> new CapabilityResults(
                        null, DrugContext.allergySuppressed(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void facilityOnlyPlanProducesNavigationWithoutMedicineSpecificRefusal() {
        MermAidAnswer answer = composer.compose(
                plan(
                        ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE,
                        Set.of(ResponsePlan.Capability.FACILITY_LOOKUP),
                        pharmacy(
                                FacilityOperationPreference.OPEN_OR_UNKNOWN,
                                ResponsePlan.FacilityAvailability.READY)),
                new CapabilityResults(null, null, Set.of()));

        assertFacilityAction(answer, "pharmacy", FacilityOperationPreference.OPEN_OR_UNKNOWN);
        assertThat(answer.drugs()).isEmpty();
        assertThat(answer.sourceRefs()).isEmpty();
        assertThat(answer.summary()).doesNotContainIgnoringCase("medicine information");
    }

    @ParameterizedTest
    @MethodSource("medicineLookupFailures")
    void facilityAndGeneralExplanationSurviveMedicineLookupFailure(CapabilityFailure failure) {
        RenderedGeneralExplanation explanation = mildFeverExplanation();
        MermAidAnswer answer = composer.compose(
                plan(
                        ResponsePlan.ResponseMode.T2_ANSWER_WITH_CONSULTATION,
                        Set.of(
                                ResponsePlan.Capability.GENERAL_EXPLANATION,
                                ResponsePlan.Capability.FACILITY_LOOKUP,
                                ResponsePlan.Capability.OFFICIAL_MEDICINE_LOOKUP,
                                ResponsePlan.Capability.PROFESSIONAL_CONSULTATION),
                        pharmacy(
                                FacilityOperationPreference.ANY,
                                ResponsePlan.FacilityAvailability.READY)),
                new CapabilityResults(explanation, null, Set.of(failure)));

        assertThat(answer.summary()).contains(explanation.summary());
        assertFacilityAction(answer, "pharmacy", FacilityOperationPreference.ANY);
        assertThat(answer.drugs()).isEmpty();
        assertThat(answer.sourceRefs()).isEmpty();
    }

    @Test
    void facilityAndCanonicalCardSurviveEnrichmentFailure() {
        MermAidAnswer canonical = canonicalMedicineAnswer();
        DrugContext context = new DrugContext("", Map.of(), List.of());
        when(medicineBuilder.build(context)).thenReturn(Optional.of(canonical));

        MermAidAnswer answer = composer.compose(
                plan(
                        ResponsePlan.ResponseMode.T2_ANSWER_WITH_CONSULTATION,
                        Set.of(
                                ResponsePlan.Capability.FACILITY_LOOKUP,
                                ResponsePlan.Capability.OFFICIAL_MEDICINE_LOOKUP,
                                ResponsePlan.Capability.PROFESSIONAL_CONSULTATION),
                        pharmacy(
                                FacilityOperationPreference.ANY,
                                ResponsePlan.FacilityAvailability.READY)),
                new CapabilityResults(
                        null, context, Set.of(CapabilityFailure.ENRICHMENT_UNAVAILABLE)));

        assertFacilityAction(answer, "pharmacy", FacilityOperationPreference.ANY);
        assertThat(answer.drugs()).containsExactlyElementsOf(canonical.drugs());
        assertThat(answer.sourceRefs()).containsExactlyElementsOf(canonical.sourceRefs());
    }

    @Test
    void missingLocationProducesHonestPromptInsteadOfFalseEmptyOrMapAction() {
        MermAidAnswer answer = composer.compose(
                plan(
                        ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE,
                        Set.of(ResponsePlan.Capability.FACILITY_LOOKUP),
                        pharmacy(
                                FacilityOperationPreference.ANY,
                                ResponsePlan.FacilityAvailability.LOCATION_REQUIRED)),
                new CapabilityResults(
                        null, null, Set.of(CapabilityFailure.LOCATION_UNAVAILABLE)));

        assertThat(answer.summary()).containsIgnoringCase("location");
        assertThat(answer.uiActions()).noneMatch(UiAction.OpenFacilityMap.class::isInstance);
    }

    @Test
    void neutralLegalInformationNamesOfficialSourcesWithoutInterpretingTheLaw() {
        ResponsePlan plan = new ResponsePlan(
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE,
                Set.of(ResponsePlan.Capability.OFFICIAL_SOURCE_NAVIGATION),
                ResponsePlan.ConfidenceBucket.HIGH,
                Set.of(ResponsePlan.ReasonCode.NEUTRAL_LEGAL_INFORMATION),
                null);

        MermAidAnswer answer = composer.compose(plan, new CapabilityResults(null, null, Set.of()));

        assertThat(answer.summary())
                .contains("National Law Information Center", "Ministry of Food and Drug Safety")
                .containsIgnoringCase("does not interpret");
        assertThat(answer.drugs()).isEmpty();
        assertThat(answer.uiActions()).isEmpty();
    }

    @Test
    void t5UsesServerRefusalWithoutRepeatingOperationalDetails() {
        ResponsePlan plan = new ResponsePlan(
                ResponsePlan.ResponseMode.T5_REFUSE_ILLEGAL_ASSISTANCE,
                Set.of(ResponsePlan.Capability.ILLEGAL_ASSISTANCE_REFUSAL),
                ResponsePlan.ConfidenceBucket.HIGH,
                Set.of(ResponsePlan.ReasonCode.EXPLICIT_CONTROL_EVASION),
                null);

        MermAidAnswer answer = composer.compose(plan, new CapabilityResults(null, null, Set.of()));

        assertThat(answer.answerId()).isEqualTo("server-illegal-assistance-refusal");
        assertThat(answer.summary()).containsIgnoringCase("cannot help");
        assertThat(answer.drugs()).isEmpty();
        assertThat(answer.uiActions()).isEmpty();
        assertThat(answer.sourceRefs()).isEmpty();
    }

    @Test
    void t4UsesTheExactServerTriageReasonAndNeverCarriesMedicine() {
        ResponsePlan plan = new ResponsePlan(
                ResponsePlan.ResponseMode.T4_EMERGENCY,
                Set.of(ResponsePlan.Capability.EMERGENCY_RESPONSE),
                ResponsePlan.ConfidenceBucket.HIGH,
                Set.of(ResponsePlan.ReasonCode.EMERGENCY_RED_FLAG),
                null,
                new ResponsePlan.EmergencyDecision("BREATHING"));

        MermAidAnswer answer = composer.compose(plan, new CapabilityResults(null, null, Set.of()));

        assertThat(answer.answerId()).isEqualTo("triage-breathing");
        assertThat(answer.urgency().level()).isEqualTo(MermAidAnswer.Urgency.Level.EMERGENCY);
        assertThat(answer.urgency().reasonCodes()).containsExactly("BREATHING");
        assertThat(answer.drugs()).isEmpty();
        assertThat(answer.uiActions())
                .singleElement()
                .isInstanceOf(UiAction.ShowEmergencyCall.class);
    }

    @Test
    void t3RefusalDoesNotRepeatEvenValidatedGeneralExplanation() {
        RenderedGeneralExplanation explanation = mildFeverExplanation();
        ResponsePlan plan = new ResponsePlan(
                ResponsePlan.ResponseMode.T3_REFUSE_CLINICAL_AUTHORITY,
                Set.of(ResponsePlan.Capability.CLINICAL_REFUSAL),
                ResponsePlan.ConfidenceBucket.HIGH,
                Set.of(ResponsePlan.ReasonCode.CLINICAL_AUTHORITY_REQUEST),
                null);

        MermAidAnswer answer = composer.compose(
                plan, new CapabilityResults(explanation, null, Set.of()));

        assertThat(answer.answerId()).isEqualTo("server-clinical-authority-refusal");
        assertThat(answer.summary()).containsIgnoringCase("cannot diagnose");
        assertThat(answer.summary()).doesNotContain(explanation.summary());
        assertThat(answer.drugs()).isEmpty();
    }

    private static Stream<CapabilityFailure> medicineLookupFailures() {
        return Stream.of(
                CapabilityFailure.PASS_ONE_UNAVAILABLE,
                CapabilityFailure.PUBLIC_DATA_UNAVAILABLE);
    }

    private static RenderedGeneralExplanation mildFeverExplanation() {
        GeneralExplanationPipeline pipeline = new GeneralExplanationPipeline(input ->
                new SemanticReviewDecision(Verdict.ALLOW, Set.of(), ConfidenceBucket.HIGH));
        ModelGeneralExplanationDraft draft = new ModelGeneralExplanationDraft(List.of(
                new ModelSymptomPossibility(
                        GeneralSubject.MILD_FEVER,
                        List.of(GeneralConcept.COMMON_VIRAL_INFECTIONS))));
        return pipeline.process(
                        "I have a mild fever",
                        draft,
                        Set.of(),
                        ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE)
                .explanation()
                .orElseThrow();
    }

    private static ResponsePlan plan(
            ResponsePlan.ResponseMode mode,
            Set<ResponsePlan.Capability> capabilities,
            ResponsePlan.FacilityIntent facilityIntent) {
        return new ResponsePlan(
                mode,
                capabilities,
                ResponsePlan.ConfidenceBucket.HIGH,
                Set.of(ResponsePlan.ReasonCode.FACILITY_REQUEST),
                facilityIntent);
    }

    private static ResponsePlan.FacilityIntent pharmacy(
            FacilityOperationPreference operationPreference,
            ResponsePlan.FacilityAvailability availability) {
        return new ResponsePlan.FacilityIntent(
                Set.of(FacilityType.PHARMACY), operationPreference, availability);
    }

    private static void assertFacilityAction(
            MermAidAnswer answer,
            String type,
            FacilityOperationPreference operationPreference) {
        assertThat(answer.uiActions())
                .singleElement()
                .isInstanceOfSatisfying(UiAction.OpenFacilityMap.class, action -> {
                    assertThat(action.payload().types()).containsExactly(type);
                    assertThat(action.payload().operationPreference()).isEqualTo(operationPreference);
                    assertThat(action.payload().openNow()).isNull();
                });
    }

    private static MermAidAnswer canonicalMedicineAnswer() {
        SourceRef source = new SourceRef(
                "source-1",
                "식품의약품안전처",
                "record-1",
                Instant.parse("2026-07-16T00:00:00Z"),
                SourceRef.DataMode.FIXTURE,
                "Official medicine record");
        MermAidAnswer.DrugCard card = new MermAidAnswer.DrugCard(
                "drug-1",
                "시험약",
                null,
                List.of(new MermAidAnswer.Ingredient(
                        null, "Acetaminophen", "acetaminophen", null, null)),
                null,
                "공식 용법 원문",
                null,
                List.of(),
                MermAidAnswer.DrugCard.PrescriptionStatus.UNKNOWN,
                AllergyCheck.unknown("A pharmacist must confirm allergy suitability."),
                source.id());
        return new MermAidAnswer(
                MermAidAnswer.SCHEMA_VERSION,
                "server-official-drug-cards",
                "en",
                MermAidAnswer.DataStatus.FIXTURE,
                new MermAidAnswer.Urgency(
                        MermAidAnswer.Urgency.Level.UNKNOWN,
                        "Urgency was not assessed",
                        "Consult a licensed professional.",
                        List.of(),
                        List.of()),
                "Official medicine records were found and verified.",
                List.of(),
                List.of(),
                List.of(card),
                List.of(),
                List.of(source),
                List.of(),
                StructuredOutputFallback.DISCLAIMER);
    }
}
