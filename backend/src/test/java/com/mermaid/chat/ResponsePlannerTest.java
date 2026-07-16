package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.chat.ResponsePlanner.FacilityRuntime;
import com.mermaid.chat.ResponsePlanner.PlanningInput;
import com.mermaid.facility.domain.FacilityType;
import com.mermaid.facility.domain.FacilityOperationPreference;
import java.io.InputStream;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class ResponsePlannerTest {

    private static final Set<String> REQUIRED_FACILITY_CASES = Set.of(
            "facility-pharmacy-nearest",
            "facility-pharmacy-open-now",
            "facility-hospital-nearest",
            "facility-hospital-open-now",
            "facility-er-nearest",
            "facility-location-unavailable",
            "facility-adapter-unavailable",
            "facility-mixed-symptom-medicine-pharmacy");

    private final ResponsePlanner planner = new ResponsePlanner(new EmergencyTriage());

    @ParameterizedTest(name = "{0}")
    @MethodSource("proposedLaunchCases")
    void resolvesProposedLaunchBoundary(String id, PlannerCase testCase) {
        AtomicInteger adviceCalls = new AtomicInteger();

        ResponsePlan actual = planner.plan(testCase.planningInput(), () -> {
            adviceCalls.incrementAndGet();
            return testCase.modelAdvice();
        });

        assertThat(adviceCalls).hasValue(testCase.expectedAdviceCalls());
        assertThat(actual.mode().name()).isEqualTo(testCase.expectedMode());
        assertThat(actual.capabilities())
                .extracting(Enum::name)
                .containsExactlyInAnyOrderElementsOf(testCase.expectedCapabilities());
        assertThat(actual.confidence().name()).isEqualTo(testCase.expectedConfidence());
        assertThat(actual.reasonCodes())
                .extracting(Enum::name)
                .containsExactlyInAnyOrderElementsOf(testCase.expectedReasonCodes());

        if (testCase.expectedFacilityTypes().isEmpty()) {
            assertThat(actual.facilityIntent()).isNull();
        } else {
            assertThat(actual.facilityIntent()).isNotNull();
            assertThat(actual.facilityIntent().types())
                    .extracting(FacilityType::wire)
                    .containsExactlyInAnyOrderElementsOf(testCase.expectedFacilityTypes());
            assertThat(actual.facilityIntent().operationPreference().name())
                    .isEqualTo(testCase.expectedOperationPreference());
            assertThat(actual.facilityIntent().availability().name())
                    .isEqualTo(testCase.expectedFacilityAvailability());
        }
    }

    @Test
    void fixtureKeepsExactlyEightPmFacilityCasesAndEightTwoCaseClinicalPairs() throws Exception {
        List<PlannerCase> cases = loadCases();

        Set<String> facilityCaseIds = cases.stream()
                .map(PlannerCase::id)
                .filter(id -> id.startsWith("facility-"))
                .collect(Collectors.toSet());
        assertThat(facilityCaseIds)
                .isEqualTo(REQUIRED_FACILITY_CASES);
        assertThat(facilityCaseIds).hasSize(8);
        assertThat(cases.stream()
                        .filter(c -> c.reviewPairId() != null)
                        .collect(Collectors.groupingBy(PlannerCase::reviewPairId, Collectors.counting())))
                .containsOnly(
                        entry("pair-1", 2L),
                        entry("pair-2", 2L),
                        entry("pair-3", 2L),
                        entry("pair-4", 2L),
                        entry("pair-5", 2L),
                        entry("pair-6", 2L),
                        entry("pair-7", 2L),
                        entry("pair-8", 2L));
    }

    @Test
    void lowConfidenceAdviceFallsBackToConsultationInsteadOfConfidentAnswer() {
        ModelPlanAdvice lowConfidence = new ModelPlanAdvice(
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE,
                Set.of(ResponsePlan.Capability.GENERAL_EXPLANATION),
                ResponsePlan.ConfidenceBucket.LOW,
                Set.of(ResponsePlan.ReasonCode.LOW_CONFIDENCE));

        ResponsePlan actual = planner.plan(
                new PlanningInput(
                        "What should I do about this?",
                        "What should I do about this?",
                        FacilityRuntime.allAvailable()),
                () -> lowConfidence);

        assertThat(actual.mode()).isEqualTo(ResponsePlan.ResponseMode.T2_ANSWER_WITH_CONSULTATION);
        assertThat(actual.capabilities())
                .containsExactly(ResponsePlan.Capability.PROFESSIONAL_CONSULTATION);
        assertThat(actual.confidence()).isEqualTo(ResponsePlan.ConfidenceBucket.LOW);
        assertThat(actual.reasonCodes()).containsExactly(ResponsePlan.ReasonCode.LOW_CONFIDENCE);
    }

    @Test
    void adviserFailureFallsBackToConsultationAndKeepsDeterministicFacilityIntent() {
        ResponsePlan actual = planner.plan(
                new PlanningInput(
                        "I have a mild fever and want a nearby pharmacy.",
                        "I have a mild fever and want a nearby pharmacy.",
                        FacilityRuntime.allAvailable()),
                () -> {
                    throw new IllegalStateException("provider text must not escape");
                });

        assertThat(actual.mode()).isEqualTo(ResponsePlan.ResponseMode.T2_ANSWER_WITH_CONSULTATION);
        assertThat(actual.capabilities())
                .containsExactlyInAnyOrder(
                        ResponsePlan.Capability.PROFESSIONAL_CONSULTATION,
                        ResponsePlan.Capability.FACILITY_LOOKUP);
        assertThat(actual.reasonCodes())
                .containsExactlyInAnyOrder(
                        ResponsePlan.ReasonCode.LOW_CONFIDENCE,
                        ResponsePlan.ReasonCode.FACILITY_REQUEST);
        assertThat(actual.facilityIntent().operationPreference())
                .isEqualTo(FacilityOperationPreference.ANY);
    }

    @Test
    void modelCannotSmuggleFacilityOrIllegalRefusalCapabilities() {
        ModelPlanAdvice malicious = new ModelPlanAdvice(
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE,
                Set.of(
                        ResponsePlan.Capability.GENERAL_EXPLANATION,
                        ResponsePlan.Capability.FACILITY_LOOKUP,
                        ResponsePlan.Capability.ILLEGAL_ASSISTANCE_REFUSAL),
                ResponsePlan.ConfidenceBucket.HIGH,
                Set.of(
                        ResponsePlan.ReasonCode.GENERAL_INFORMATION,
                        ResponsePlan.ReasonCode.EXPLICIT_CONTROL_EVASION));

        ResponsePlan actual = planner.plan(
                new PlanningInput(
                        "What does inflammation mean?",
                        "What does inflammation mean?",
                        FacilityRuntime.allAvailable()),
                () -> malicious);

        assertThat(actual.capabilities())
                .containsExactly(ResponsePlan.Capability.GENERAL_EXPLANATION);
        assertThat(actual.reasonCodes())
                .containsExactly(ResponsePlan.ReasonCode.GENERAL_INFORMATION);
        assertThat(actual.facilityIntent()).isNull();
    }

    @Test
    void emergencyPlanRetainsTheServerTriageReasonCode() {
        ResponsePlan actual = planner.plan(
                new PlanningInput(
                        "Where is the nearest hospital?",
                        "I have crushing chest pain. Where is the nearest hospital?",
                        FacilityRuntime.allAvailable()),
                () -> ModelPlanAdvice.none());

        assertThat(actual.mode()).isEqualTo(ResponsePlan.ResponseMode.T4_EMERGENCY);
        assertThat(actual.emergencyDecision()).isNotNull();
        assertThat(actual.emergencyDecision().reasonCode()).isEqualTo("CHEST_PAIN");
    }

    @Test
    void emergencyOverridesExplicitControlEvasionWithoutCallingTheAdviser() {
        AtomicInteger adviceCalls = new AtomicInteger();

        ResponsePlan actual = planner.plan(
                new PlanningInput(
                        "How can I buy fentanyl on the black market?",
                        "I cannot breathe. How can I buy fentanyl on the black market?",
                        FacilityRuntime.allAvailable()),
                () -> {
                    adviceCalls.incrementAndGet();
                    return ModelPlanAdvice.none();
                });

        assertThat(adviceCalls).hasValue(0);
        assertThat(actual.mode()).isEqualTo(ResponsePlan.ResponseMode.T4_EMERGENCY);
        assertThat(actual.capabilities())
                .containsExactly(ResponsePlan.Capability.EMERGENCY_RESPONSE);
        assertThat(actual.emergencyDecision().reasonCode()).isEqualTo("BREATHING");
    }

    @Test
    void modelEmergencySignalIsCanonicalizedWithoutCapabilitySmuggling() {
        ModelPlanAdvice escalation = new ModelPlanAdvice(
                ResponsePlan.ResponseMode.T4_EMERGENCY,
                Set.of(
                        ResponsePlan.Capability.EMERGENCY_RESPONSE,
                        ResponsePlan.Capability.OFFICIAL_MEDICINE_LOOKUP),
                ResponsePlan.ConfidenceBucket.MEDIUM,
                Set.of(ResponsePlan.ReasonCode.GENERAL_INFORMATION));

        ResponsePlan actual = planner.plan(
                new PlanningInput(
                        "I feel suddenly much worse.",
                        "I feel suddenly much worse.",
                        FacilityRuntime.allAvailable()),
                () -> escalation);

        assertThat(actual.mode()).isEqualTo(ResponsePlan.ResponseMode.T4_EMERGENCY);
        assertThat(actual.capabilities())
                .containsExactly(ResponsePlan.Capability.EMERGENCY_RESPONSE);
        assertThat(actual.emergencyDecision().reasonCode()).isEqualTo("MODEL_ESCALATION");
    }

    @Test
    void careDecisionQuestionDoesNotBecomeFacilityNavigation() {
        ModelPlanAdvice consultation = new ModelPlanAdvice(
                ResponsePlan.ResponseMode.T2_ANSWER_WITH_CONSULTATION,
                Set.of(ResponsePlan.Capability.PROFESSIONAL_CONSULTATION),
                ResponsePlan.ConfidenceBucket.HIGH,
                Set.of(ResponsePlan.ReasonCode.PROFESSIONAL_CONTEXT));

        ResponsePlan actual = planner.plan(
                new PlanningInput(
                        "Show me signs that I need a hospital.",
                        "Show me signs that I need a hospital.",
                        FacilityRuntime.allAvailable()),
                () -> consultation);

        assertThat(actual.capabilities())
                .containsExactly(ResponsePlan.Capability.PROFESSIONAL_CONSULTATION);
        assertThat(actual.facilityIntent()).isNull();
    }

    @Test
    void multipleFacilityTypesDoNotSilentlyCollapseToTheFirstType() {
        ResponsePlan actual = planner.plan(
                new PlanningInput(
                        "Show me nearby pharmacies and hospitals.",
                        "Show me nearby pharmacies and hospitals.",
                        FacilityRuntime.allAvailable()),
                ModelPlanAdvice::none);

        assertThat(actual.mode()).isEqualTo(ResponsePlan.ResponseMode.T2_ANSWER_WITH_CONSULTATION);
        assertThat(actual.facilityIntent()).isNull();
        assertThat(actual.capabilities())
                .containsExactly(ResponsePlan.Capability.PROFESSIONAL_CONSULTATION);
    }

    @Test
    void aControlledSubstanceNameWithoutEvasionIntentDoesNotTriggerT5() {
        ModelPlanAdvice explanation = new ModelPlanAdvice(
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE,
                Set.of(ResponsePlan.Capability.GENERAL_EXPLANATION),
                ResponsePlan.ConfidenceBucket.HIGH,
                Set.of(ResponsePlan.ReasonCode.GENERAL_INFORMATION));

        ResponsePlan actual = planner.plan(
                new PlanningInput(
                        "What is fentanyl used for in general?",
                        "What is fentanyl used for in general?",
                        FacilityRuntime.allAvailable()),
                () -> explanation);

        assertThat(actual.mode())
                .isEqualTo(ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE);
        assertThat(actual.capabilities())
                .containsExactly(ResponsePlan.Capability.GENERAL_EXPLANATION);
    }

    @Test
    void otcAcquisitionWithoutAPrescriptionIsNotDeclaredIllegal() {
        ResponsePlan actual = planner.plan(
                new PlanningInput(
                        "Where can I buy ibuprofen without a prescription?",
                        "Where can I buy ibuprofen without a prescription?",
                        FacilityRuntime.allAvailable()),
                ModelPlanAdvice::none);

        assertThat(actual.mode()).isEqualTo(ResponsePlan.ResponseMode.T2_ANSWER_WITH_CONSULTATION);
        assertThat(actual.reasonCodes())
                .containsExactly(ResponsePlan.ReasonCode.LOW_CONFIDENCE);
    }

    @Test
    void identifyingAFakePrescriptionIsNotOperationalForgeryAssistance() {
        ResponsePlan actual = planner.plan(
                new PlanningInput(
                        "How can I identify a fake prescription?",
                        "How can I identify a fake prescription?",
                        FacilityRuntime.allAvailable()),
                ModelPlanAdvice::none);

        assertThat(actual.mode()).isEqualTo(ResponsePlan.ResponseMode.T2_ANSWER_WITH_CONSULTATION);
    }

    @Test
    void negatedAcquisitionIntentIsNotT5() {
        ResponsePlan actual = planner.plan(
                new PlanningInput(
                        "I do not want to buy fentanyl without a prescription.",
                        "I do not want to buy fentanyl without a prescription.",
                        FacilityRuntime.allAvailable()),
                ModelPlanAdvice::none);

        assertThat(actual.mode()).isEqualTo(ResponsePlan.ResponseMode.T2_ANSWER_WITH_CONSULTATION);
    }

    @Test
    void explicitEvasionCannotBeHiddenInsideANeutralLegalQuestion() {
        ResponsePlan actual = planner.plan(
                new PlanningInput(
                        "Is fentanyl prescribed legally in Korea, and how can I buy fentanyl without a prescription?",
                        "Is fentanyl prescribed legally in Korea, and how can I buy fentanyl without a prescription?",
                        FacilityRuntime.allAvailable()),
                ModelPlanAdvice::none);

        assertThat(actual.mode()).isEqualTo(ResponsePlan.ResponseMode.T5_REFUSE_ILLEGAL_ASSISTANCE);
        assertThat(actual.capabilities())
                .containsExactly(ResponsePlan.Capability.ILLEGAL_ASSISTANCE_REFUSAL);
    }

    @Test
    void findOutWhetherIShouldGoToAHospitalRemainsACareDecision() {
        ResponsePlan actual = planner.plan(
                new PlanningInput(
                        "Find out whether I should go to a hospital.",
                        "Find out whether I should go to a hospital.",
                        FacilityRuntime.allAvailable()),
                ModelPlanAdvice::none);

        assertThat(actual.facilityIntent()).isNull();
        assertThat(actual.mode()).isEqualTo(ResponsePlan.ResponseMode.T2_ANSWER_WITH_CONSULTATION);
    }

    @Test
    void fillingAPrescriptionAtAPharmacyRemainsFacilityOnly() {
        AtomicInteger adviceCalls = new AtomicInteger();

        ResponsePlan actual = planner.plan(
                new PlanningInput(
                        "Where is the nearest pharmacy to fill my prescription?",
                        "Where is the nearest pharmacy to fill my prescription?",
                        FacilityRuntime.allAvailable()),
                () -> {
                    adviceCalls.incrementAndGet();
                    return ModelPlanAdvice.none();
                });

        assertThat(adviceCalls).hasValue(0);
        assertThat(actual.capabilities())
                .containsExactly(ResponsePlan.Capability.FACILITY_LOOKUP);
    }

    @Test
    void hospitalEmergencyRoomIsOneErDestination() {
        ResponsePlan actual = planner.plan(
                new PlanningInput(
                        "Where is the nearest hospital emergency room?",
                        "Where is the nearest hospital emergency room?",
                        FacilityRuntime.allAvailable()),
                ModelPlanAdvice::none);

        assertThat(actual.facilityIntent().types())
                .containsExactly(FacilityType.EMERGENCY_ROOM);
    }

    @Test
    void openedRightNowUsesOpenOrUnknown() {
        ResponsePlan actual = planner.plan(
                new PlanningInput(
                        "Where is the closest pharmacy opened right now?",
                        "Where is the closest pharmacy opened right now?",
                        FacilityRuntime.allAvailable()),
                ModelPlanAdvice::none);

        assertThat(actual.facilityIntent().operationPreference())
                .isEqualTo(FacilityOperationPreference.OPEN_OR_UNKNOWN);
        assertThat(actual.reasonCodes())
                .contains(ResponsePlan.ReasonCode.OPEN_NOW_REQUEST);
    }

    @Test
    void protectedModesRejectCapabilitySmuggling() {
        assertThatThrownBy(() -> new ResponsePlan(
                        ResponsePlan.ResponseMode.T4_EMERGENCY,
                        Set.of(
                                ResponsePlan.Capability.EMERGENCY_RESPONSE,
                                ResponsePlan.Capability.OFFICIAL_MEDICINE_LOOKUP),
                        ResponsePlan.ConfidenceBucket.HIGH,
                        Set.of(ResponsePlan.ReasonCode.EMERGENCY_RED_FLAG),
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResponsePlan(
                        ResponsePlan.ResponseMode.T5_REFUSE_ILLEGAL_ASSISTANCE,
                        Set.of(
                                ResponsePlan.Capability.ILLEGAL_ASSISTANCE_REFUSAL,
                                ResponsePlan.Capability.GENERAL_EXPLANATION),
                        ResponsePlan.ConfidenceBucket.HIGH,
                        Set.of(ResponsePlan.ReasonCode.EXPLICIT_CONTROL_EVASION),
                        null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResponsePlan(
                        ResponsePlan.ResponseMode.T3_REFUSE_CLINICAL_AUTHORITY,
                        Set.of(ResponsePlan.Capability.GENERAL_EXPLANATION),
                        ResponsePlan.ConfidenceBucket.HIGH,
                        Set.of(ResponsePlan.ReasonCode.CLINICAL_AUTHORITY_REQUEST),
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> proposedLaunchCases() throws Exception {
        return loadCases().stream().map(testCase -> Arguments.of(testCase.id(), testCase));
    }

    private static List<PlannerCase> loadCases() throws Exception {
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        try (InputStream input = ResponsePlannerTest.class
                .getClassLoader()
                .getResourceAsStream("chat/response-planner-cases.json")) {
            assertThat(input).as("response planner fixture").isNotNull();
            return mapper.readValue(input, new TypeReference<>() {});
        }
    }

    record PlannerCase(
            String id,
            String reviewPairId,
            String input,
            String allUserText,
            Boolean locationAvailable,
            List<String> unavailableFacilityTypes,
            String advisedMode,
            List<String> advisedCapabilities,
            String adviceConfidence,
            List<String> adviceReasonCodes,
            Integer expectedAdviceCalls,
            String expectedMode,
            List<String> expectedCapabilities,
            String expectedConfidence,
            List<String> expectedReasonCodes,
            List<String> expectedFacilityTypes,
            String expectedOperationPreference,
            String expectedFacilityAvailability) {

        PlanningInput planningInput() {
            Set<FacilityType> deployed = EnumSet.allOf(FacilityType.class);
            unavailableFacilityTypes.stream()
                    .map(FacilityType::from)
                    .forEach(deployed::remove);
            return new PlanningInput(
                    input,
                    allUserText == null ? input : allUserText,
                    new FacilityRuntime(locationAvailable, deployed));
        }

        ModelPlanAdvice modelAdvice() {
            if (advisedMode == null) {
                return ModelPlanAdvice.none();
            }
            return new ModelPlanAdvice(
                    ResponsePlan.ResponseMode.valueOf(advisedMode),
                    advisedCapabilities.stream()
                            .map(ResponsePlan.Capability::valueOf)
                            .collect(Collectors.toUnmodifiableSet()),
                    ResponsePlan.ConfidenceBucket.valueOf(adviceConfidence),
                    adviceReasonCodes.stream()
                            .map(ResponsePlan.ReasonCode::valueOf)
                            .collect(Collectors.toUnmodifiableSet()));
        }

        PlannerCase {
            locationAvailable = locationAvailable == null || locationAvailable;
            unavailableFacilityTypes = unavailableFacilityTypes == null
                    ? List.of()
                    : List.copyOf(unavailableFacilityTypes);
            advisedCapabilities = advisedCapabilities == null ? List.of() : List.copyOf(advisedCapabilities);
            adviceReasonCodes = adviceReasonCodes == null ? List.of() : List.copyOf(adviceReasonCodes);
            expectedAdviceCalls = expectedAdviceCalls == null ? 0 : expectedAdviceCalls;
            expectedCapabilities = List.copyOf(expectedCapabilities);
            expectedConfidence = expectedConfidence == null ? "HIGH" : expectedConfidence;
            expectedReasonCodes = List.copyOf(expectedReasonCodes);
            expectedFacilityTypes =
                    expectedFacilityTypes == null ? List.of() : List.copyOf(expectedFacilityTypes);
        }
    }

}
