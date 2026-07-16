package com.mermaid.chat;

import com.mermaid.chat.DrugContextRetriever.DrugContext;
import com.mermaid.chat.GeneralExplanationPipeline.RenderedGeneralExplanation;
import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.chat.dto.UiAction;
import com.mermaid.facility.domain.FacilityType;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Composes successful capability results without letting one failed capability erase another. */
@Component
final class ResponseAnswerComposer {

    private static final int DEFAULT_RADIUS_M = 1_000;
    private static final String DEFAULT_URGENCY_TITLE = "Urgency was not assessed";
    private static final String DEFAULT_URGENCY_MESSAGE =
            "This general information does not determine how urgent your symptoms are. "
                    + "If symptoms are severe or worsening, seek medical care.";

    // PM/QA-reviewable draft copy under the SJY051-approved DEV-603 tier semantics (2026-07-16).
    // These strings are server-owned; no user or provider text is interpolated into them.
    private static final String FACILITY_SUMMARY =
            "You can use the map below to look for nearby care. Confirm opening hours before you go.";
    private static final String LOCATION_REQUIRED_SUMMARY =
            "Choose a location or allow location access before looking for nearby care. "
                    + "The service will not invent which facility is closest.";
    private static final String ADAPTER_UNAVAILABLE_SUMMARY =
            "This facility search is unavailable right now. The service will not present an empty "
                    + "result as though no facility exists.";
    private static final String CONSULTATION_SUMMARY =
            "A licensed doctor or pharmacist can consider your symptoms and personal medical context.";
    private static final String MEDICINE_UNAVAILABLE_SUMMARY =
            "Official medicine information was unavailable for this part of the request, so no "
                    + "medicine card is shown.";
    private static final String MEDICINE_EMPTY_SUMMARY =
            "No official medicine record matched this part of the request, so no medicine card is shown.";
    private static final String MEDICINE_INCONSISTENT_SUMMARY =
            "Official medicine records could not be prepared as verified cards for this part of the "
                    + "request, so no medicine card is shown.";
    private static final String CLINICAL_REFUSAL_SUMMARY =
            "I cannot diagnose you, choose a treatment or personal dose, or certify that a medicine "
                    + "is appropriate for you. A licensed doctor or pharmacist can help with that decision.";
    private static final String ILLEGAL_ASSISTANCE_REFUSAL_SUMMARY =
            "I cannot help bypass prescription or drug-control requirements. For medical care, "
                    + "contact a licensed doctor or pharmacist.";
    private static final String OFFICIAL_LEGAL_SOURCE_SUMMARY =
            "For current Korean rules, consult the National Law Information Center and Ministry "
                    + "of Food and Drug Safety. This service points to those official sources but "
                    + "does not interpret how the law applies to a person or situation.";

    private final ServerAuthoredAnswerBuilder medicineAnswerBuilder;
    private final EmergencyTriage emergencyTriage;

    ResponseAnswerComposer(
            ServerAuthoredAnswerBuilder medicineAnswerBuilder,
            EmergencyTriage emergencyTriage) {
        this.medicineAnswerBuilder = Objects.requireNonNull(medicineAnswerBuilder);
        this.emergencyTriage = Objects.requireNonNull(emergencyTriage);
    }

    MermAidAnswer compose(ResponsePlan plan, CapabilityResults results) {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(results, "results");
        if (plan.mode() == ResponsePlan.ResponseMode.T4_EMERGENCY) {
            return emergencyTriage.emergencyAnswer(
                    plan.emergencyDecision().reasonCode());
        }
        if (plan.mode() == ResponsePlan.ResponseMode.T5_REFUSE_ILLEGAL_ASSISTANCE) {
            return fixedAnswer(
                    "server-illegal-assistance-refusal",
                    ILLEGAL_ASSISTANCE_REFUSAL_SUMMARY,
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    MermAidAnswer.DataStatus.UNAVAILABLE);
        }

        Optional<MermAidAnswer> canonicalMedicine = Optional.empty();
        if (plan.capabilities().contains(ResponsePlan.Capability.OFFICIAL_MEDICINE_LOOKUP)
                && results.medicineContext() != null) {
            canonicalMedicine = medicineAnswerBuilder.build(results.medicineContext());
        }

        MermAidAnswer medicine = canonicalMedicine.orElse(null);
        return fixedAnswer(
                answerId(plan, medicine),
                summary(plan, results, medicine),
                medicine == null ? List.of() : medicine.clarifyingQuestions(),
                medicine == null ? List.of() : medicine.guidance(),
                medicine == null ? List.of() : medicine.drugs(),
                uiActions(plan),
                medicine == null ? List.of() : medicine.sourceRefs(),
                medicine == null ? List.of() : medicine.warnings(),
                medicine == null ? MermAidAnswer.DataStatus.UNAVAILABLE : medicine.dataStatus(),
                medicine == null ? defaultUrgency() : medicine.urgency());
    }

    private static String summary(
            ResponsePlan plan, CapabilityResults results, MermAidAnswer canonicalMedicine) {
        if (plan.mode() == ResponsePlan.ResponseMode.T3_REFUSE_CLINICAL_AUTHORITY) {
            return withFacilityState(CLINICAL_REFUSAL_SUMMARY, plan.facilityIntent());
        }

        List<String> parts = new ArrayList<>();
        if (results.generalExplanation() != null
                && plan.capabilities().contains(ResponsePlan.Capability.GENERAL_EXPLANATION)) {
            parts.add(results.generalExplanation().summary());
        }
        if (canonicalMedicine != null) {
            parts.add(canonicalMedicine.summary());
        } else {
            String medicineState = medicineStateSummary(plan, results);
            if (medicineState != null) {
                parts.add(medicineState);
            }
        }
        if (plan.capabilities().contains(ResponsePlan.Capability.OFFICIAL_SOURCE_NAVIGATION)) {
            parts.add(OFFICIAL_LEGAL_SOURCE_SUMMARY);
        }
        if (plan.capabilities().contains(ResponsePlan.Capability.PROFESSIONAL_CONSULTATION)) {
            parts.add(CONSULTATION_SUMMARY);
        }
        String facilityState = facilitySummary(plan.facilityIntent());
        if (facilityState != null) {
            parts.add(facilityState);
        }
        if (parts.isEmpty()) {
            parts.add("I could not prepare a bounded response for this request. "
                    + "Please ask a licensed doctor or pharmacist.");
        }
        return String.join(" ", parts);
    }

    private static String medicineStateSummary(ResponsePlan plan, CapabilityResults results) {
        if (!plan.capabilities().contains(ResponsePlan.Capability.OFFICIAL_MEDICINE_LOOKUP)) {
            return null;
        }
        if (results.failures().contains(CapabilityFailure.PASS_ONE_UNAVAILABLE)
                || results.failures().contains(CapabilityFailure.PUBLIC_DATA_UNAVAILABLE)) {
            return MEDICINE_UNAVAILABLE_SUMMARY;
        }
        DrugContext context = results.medicineContext();
        if (context == null) {
            return MEDICINE_UNAVAILABLE_SUMMARY;
        }
        if (context.groundedDrugs().isEmpty() && context.sources().isEmpty()) {
            return MEDICINE_EMPTY_SUMMARY;
        }
        return MEDICINE_INCONSISTENT_SUMMARY;
    }

    private static String withFacilityState(
            String summary, ResponsePlan.FacilityIntent facilityIntent) {
        String facility = facilitySummary(facilityIntent);
        return facility == null ? summary : summary + " " + facility;
    }

    private static String facilitySummary(ResponsePlan.FacilityIntent facilityIntent) {
        if (facilityIntent == null) {
            return null;
        }
        return switch (facilityIntent.availability()) {
            case READY -> FACILITY_SUMMARY;
            case LOCATION_REQUIRED -> LOCATION_REQUIRED_SUMMARY;
            case ADAPTER_UNAVAILABLE -> ADAPTER_UNAVAILABLE_SUMMARY;
        };
    }

    private static List<UiAction> facilityActions(ResponsePlan.FacilityIntent facilityIntent) {
        if (facilityIntent == null
                || facilityIntent.availability() != ResponsePlan.FacilityAvailability.READY) {
            return List.of();
        }
        List<String> types = facilityIntent.types().stream()
                .sorted(Comparator.comparing(FacilityType::wire))
                .map(FacilityType::wire)
                .toList();
        return List.of(new UiAction.OpenFacilityMap(new UiAction.MapPayload(
                types, facilityIntent.operationPreference(), DEFAULT_RADIUS_M)));
    }

    private static List<UiAction> uiActions(ResponsePlan plan) {
        List<UiAction> actions = new ArrayList<>();
        if (plan.capabilities().contains(ResponsePlan.Capability.OFFICIAL_SOURCE_NAVIGATION)) {
            actions.add(UiAction.OpenOfficialSource.koreanNarcoticsControlAct());
            actions.add(UiAction.OpenOfficialSource.mfdsMedicalNarcoticAnalgesicStandards());
        }
        actions.addAll(facilityActions(plan.facilityIntent()));
        return List.copyOf(actions);
    }

    private static String answerId(ResponsePlan plan, MermAidAnswer medicine) {
        if (medicine != null) {
            return medicine.answerId();
        }
        return switch (plan.mode()) {
            case T1_ANSWER_GENERAL_OR_LOCATE_CARE -> "server-planned-t1";
            case T2_ANSWER_WITH_CONSULTATION -> "server-planned-t2";
            case T3_REFUSE_CLINICAL_AUTHORITY -> "server-clinical-authority-refusal";
            case T4_EMERGENCY, T5_REFUSE_ILLEGAL_ASSISTANCE -> throw new IllegalStateException(
                    "protected answers return before general composition");
        };
    }

    private static MermAidAnswer fixedAnswer(
            String answerId,
            String summary,
            List<MermAidAnswer.Guidance> guidance,
            List<MermAidAnswer.DrugCard> drugs,
            List<UiAction> actions,
            List<com.mermaid.common.SourceRef> sources,
            MermAidAnswer.DataStatus dataStatus) {
        return fixedAnswer(
                answerId,
                summary,
                List.of(),
                guidance,
                drugs,
                actions,
                sources,
                List.of(),
                dataStatus,
                defaultUrgency());
    }

    private static MermAidAnswer fixedAnswer(
            String answerId,
            String summary,
            List<String> clarifyingQuestions,
            List<MermAidAnswer.Guidance> guidance,
            List<MermAidAnswer.DrugCard> drugs,
            List<UiAction> actions,
            List<com.mermaid.common.SourceRef> sources,
            List<String> warnings,
            MermAidAnswer.DataStatus dataStatus,
            MermAidAnswer.Urgency urgency) {
        return new MermAidAnswer(
                MermAidAnswer.SCHEMA_VERSION,
                answerId,
                "en",
                dataStatus,
                urgency,
                summary,
                List.copyOf(clarifyingQuestions),
                List.copyOf(guidance),
                List.copyOf(drugs),
                List.copyOf(actions),
                List.copyOf(sources),
                List.copyOf(warnings),
                StructuredOutputFallback.DISCLAIMER);
    }

    private static MermAidAnswer.Urgency defaultUrgency() {
        return new MermAidAnswer.Urgency(
                MermAidAnswer.Urgency.Level.UNKNOWN,
                DEFAULT_URGENCY_TITLE,
                DEFAULT_URGENCY_MESSAGE,
                List.of(),
                List.of());
    }

    /** Independently executed capability results; raw model prose cannot inhabit this shape. */
    record CapabilityResults(
            RenderedGeneralExplanation generalExplanation,
            DrugContext medicineContext,
            Set<CapabilityFailure> failures) {

        CapabilityResults {
            Objects.requireNonNull(failures, "failures");
            failures = Set.copyOf(failures);
            if (medicineContext != null && medicineContext.directAnswer().isPresent()) {
                throw new IllegalArgumentException(
                        "direct safety answers must short-circuit before capability composition");
            }
        }
    }

    enum CapabilityFailure {
        PASS_ONE_UNAVAILABLE,
        PUBLIC_DATA_UNAVAILABLE,
        ENRICHMENT_UNAVAILABLE,
        LOCATION_UNAVAILABLE,
        FACILITY_ADAPTER_UNAVAILABLE
    }
}
