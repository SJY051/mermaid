package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mermaid.chat.DrugContextRetriever.DrugContext;
import com.mermaid.chat.GeneralExplanationPolicy.ValidatedGeneralExplanation;
import com.mermaid.chat.ResponseAnswerComposer.CapabilityFailure;
import com.mermaid.chat.ResponseAnswerComposer.CapabilityResults;
import com.mermaid.chat.dto.AllergyCheck;
import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.chat.dto.UiAction;
import com.mermaid.common.SourceRef;
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
    private final ResponseAnswerComposer composer = new ResponseAnswerComposer(medicineBuilder);
    private final GeneralExplanationPolicy explanationPolicy = new GeneralExplanationPolicy();

    @Test
    void capabilityResultsCannotCarryRawModelProseOrWholeAnswer() {
        assertThat(CapabilityResults.class.getRecordComponents())
                .extracting(component -> component.getType().getName())
                .doesNotContain(String.class.getName(), MermAidAnswer.class.getName())
                .contains(
                        ValidatedGeneralExplanation.class.getName(), DrugContext.class.getName());
    }

    @Test
    void facilityOnlyPlanProducesNavigationWithoutMedicineSpecificRefusal() {
        MermAidAnswer answer = composer.compose(
                plan(
                        ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE,
                        Set.of(ResponsePlan.Capability.FACILITY_LOOKUP),
                        pharmacy(
                                ResponsePlan.OperationPreference.OPEN_OR_UNKNOWN,
                                ResponsePlan.FacilityAvailability.READY)),
                new CapabilityResults(null, null, Set.of()));

        assertFacilityAction(answer);
        assertThat(answer.drugs()).isEmpty();
        assertThat(answer.sourceRefs()).isEmpty();
        assertThat(answer.summary()).doesNotContainIgnoringCase("medicine information");
    }

    @ParameterizedTest
    @MethodSource("medicineLookupFailures")
    void facilityAndGeneralExplanationSurviveMedicineLookupFailure(CapabilityFailure failure) {
        String explanation =
                "A mild fever can happen with a common viral infection, but it has many possible causes.";
        ValidatedGeneralExplanation validated = explanationPolicy
                .validate(explanation)
                .explanation()
                .orElseThrow();

        MermAidAnswer answer = composer.compose(
                plan(
                        ResponsePlan.ResponseMode.T2_ANSWER_WITH_CONSULTATION,
                        Set.of(
                                ResponsePlan.Capability.GENERAL_EXPLANATION,
                                ResponsePlan.Capability.FACILITY_LOOKUP,
                                ResponsePlan.Capability.OFFICIAL_MEDICINE_LOOKUP,
                                ResponsePlan.Capability.PROFESSIONAL_CONSULTATION),
                        pharmacy(
                                ResponsePlan.OperationPreference.ANY,
                                ResponsePlan.FacilityAvailability.READY)),
                new CapabilityResults(validated, null, Set.of(failure)));

        assertThat(answer.summary()).contains(explanation);
        assertFacilityAction(answer);
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
                                ResponsePlan.OperationPreference.ANY,
                                ResponsePlan.FacilityAvailability.READY)),
                new CapabilityResults(
                        null, context, Set.of(CapabilityFailure.ENRICHMENT_UNAVAILABLE)));

        assertFacilityAction(answer);
        assertThat(answer.drugs()).containsExactlyElementsOf(canonical.drugs());
        assertThat(answer.sourceRefs()).containsExactlyElementsOf(canonical.sourceRefs());
        assertThat(answer.drugs().getFirst().indicationSummary()).isNull();
        assertThat(answer.drugs().getFirst().labelCautions()).isNull();
    }

    @Test
    void missingLocationProducesHonestPromptInsteadOfFalseEmptyOrMapAction() {
        MermAidAnswer answer = composer.compose(
                plan(
                        ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE,
                        Set.of(ResponsePlan.Capability.FACILITY_LOOKUP),
                        pharmacy(
                                ResponsePlan.OperationPreference.ANY,
                                ResponsePlan.FacilityAvailability.LOCATION_REQUIRED)),
                new CapabilityResults(
                        null, null, Set.of(CapabilityFailure.LOCATION_UNAVAILABLE)));

        assertThat(answer.summary()).containsIgnoringCase("location");
        assertThat(answer.uiActions()).noneMatch(UiAction.OpenFacilityMap.class::isInstance);
    }

    @Test
    void missingAdapterProducesUnavailableStateInsteadOfFalseEmpty() {
        MermAidAnswer answer = composer.compose(
                plan(
                        ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE,
                        Set.of(ResponsePlan.Capability.FACILITY_LOOKUP),
                        pharmacy(
                                ResponsePlan.OperationPreference.ANY,
                                ResponsePlan.FacilityAvailability.ADAPTER_UNAVAILABLE)),
                new CapabilityResults(
                        null, null, Set.of(CapabilityFailure.FACILITY_ADAPTER_UNAVAILABLE)));

        assertThat(answer.summary()).containsIgnoringCase("unavailable");
        assertThat(answer.uiActions()).noneMatch(UiAction.OpenFacilityMap.class::isInstance);
    }

    @Test
    void t5UsesServerRefusal() {
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

    private static Stream<CapabilityFailure> medicineLookupFailures() {
        return Stream.of(
                CapabilityFailure.PASS_ONE_UNAVAILABLE,
                CapabilityFailure.PUBLIC_DATA_UNAVAILABLE);
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
            ResponsePlan.OperationPreference operationPreference,
            ResponsePlan.FacilityAvailability availability) {
        return new ResponsePlan.FacilityIntent(
                Set.of(FacilityType.PHARMACY), operationPreference, availability);
    }

    private static void assertFacilityAction(MermAidAnswer answer) {
        assertThat(answer.uiActions()).singleElement().isInstanceOf(UiAction.OpenFacilityMap.class);
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
