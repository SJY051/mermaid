package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.entry;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.chat.ResponsePlanner.FacilityRuntime;
import com.mermaid.chat.ResponsePlanner.PlanningInput;
import com.mermaid.facility.domain.FacilityType;
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
