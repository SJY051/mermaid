package com.mermaid.chat;

import com.mermaid.facility.domain.FacilityType;
import com.mermaid.facility.domain.FacilityOperationPreference;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Adjudicates deterministic safety rules and optional model advice into a server-owned plan. */
@Component
@RequiredArgsConstructor
final class ResponsePlanner {

    private static final Pattern PHARMACY =
            Pattern.compile("\\b(?:pharmac(?:y|ies)|drugstores?)\\b");
    private static final Pattern HOSPITAL = Pattern.compile("\\bhospitals?\\b");
    private static final Pattern EMERGENCY_ROOM =
            Pattern.compile("\\b(?:ers?|emergency rooms?|emergency departments?)\\b");
    private static final Pattern HOSPITAL_EMERGENCY_ROOM = Pattern.compile(
            "\\bhospitals?\\s+(?:ers?|emergency rooms?|emergency departments?)\\b");
    private static final Pattern HOSPITAL_LANDMARK = Pattern.compile(
            "\\b(?:near|beside|by|around|next to|close to)\\s+(?:the|a|an)\\s+hospitals?\\b");
    private static final Pattern LOCATION_SIGNAL = Pattern.compile(
            "\\b(?:closest|directions?|find|locate|map|nearest|nearby|where)\\b"
                    + "|\\blook\\s+for\\b|\\b(?:near|around)\\s+(?:me|my current location)\\b");
    private static final Pattern OPEN_SIGNAL = Pattern.compile(
            "\\bopen(?:ed)?\\b(?:\\s+\\w+){0,2}\\s+\\b(?:now|currently|today|tonight)\\b"
                    + "|\\b(?:currently|still)\\s+open\\b|\\bopen\\s+at\\s+the\\s+moment\\b"
                    + "|\\b24[ -]?hours?\\b|\\b24\\s*/\\s*7\\b");
    private static final Pattern NON_FACILITY_INTENT = Pattern.compile(
            "\\b(?:allerg(?:y|ic|ies)|cold|cough|dose|fever|headache|interaction|medicine|"
                    + "medication|pain|sick|sore throat|symptoms?|tablet|treatment)\\b");
    private static final String CARE_SUBJECT =
            "(?:i|my (?:child|baby|son|daughter|mother|father|parent|partner|friend)"
                    + "|our (?:child|baby|son|daughter|parent)|the patient|he|she|they)";
    private static final Pattern CARE_DECISION = Pattern.compile(
            "\\b(?:should i|do i need to)\\s+(?:go|visit)\\b"
                    + "|\\b(?:whether|if) " + CARE_SUBJECT + " should\\s+(?:go|visit)\\b"
                    + "|\\bsigns? (?:that )?i need\\b.*\\b(?:hospital|ers?|emergency rooms?)\\b");

    private static final Pattern NEUTRAL_LEGAL_INFORMATION = Pattern.compile(
            "\\b(?:prescribed legally|legal(?:ly)? prescribed|medical narcotic)\\b"
                    + ".*\\b(?:korea|korean law|law)\\b"
                    + "|\\b(?:korea|korean law|law)\\b.*\\b(?:prescribed legally|medical narcotic)\\b");
    private static final String OPERATIONAL_REQUEST =
            "(?:how (?:can|do) i|where can i|tell me how(?: to)?|help me(?: to)?"
                    + "|give me (?:step-by-step )?(?:instructions|steps)(?: (?:on|for))?"
                    + "|what is the best way(?: to)?)";
    private static final String LAUNCH_CONTROLLED_SUBJECT =
            "(?:fentanyl(?!\\s+(?:test\\s+strips?|testing\\s+kits?)\\b)"
                    + "|controlled drug|medical narcotic|narcotic|illicit drug)";
    private static final Pattern QUOTED_SPAN = Pattern.compile(
            "\\\"(?:[^\\\"\\\\]|\\\\.)*\\\""
                    + "|(?<![\\p{L}\\p{N}])'(?:[^'\\\\]|\\\\.)*'(?![\\p{L}\\p{N}])"
                    + "|“[^”]*”|‘[^’]*’");
    private static final Pattern BLACK_MARKET = Pattern.compile(
            "\\b" + OPERATIONAL_REQUEST + "\\b.*\\b(?:buy|sell|obtain|get)\\b.*\\b"
                    + LAUNCH_CONTROLLED_SUBJECT + "\\b.*\\bblack market\\b"
                    + "|\\b" + OPERATIONAL_REQUEST + "\\b.*\\b(?:buy|sell|obtain|get)\\b.*"
                    + "\\bblack market\\b.*\\b" + LAUNCH_CONTROLLED_SUBJECT + "\\b");
    private static final Pattern WITHOUT_PRESCRIPTION = Pattern.compile(
            "\\b" + OPERATIONAL_REQUEST + "\\b.*\\b(?:buy|sell|obtain|get)\\b.*\\b"
                    + LAUNCH_CONTROLLED_SUBJECT + "\\b.*\\bwithout (?:a )?prescription\\b"
                    + "|\\b" + OPERATIONAL_REQUEST + "\\b.*\\b(?:buy|sell|obtain|get)\\b.*"
                    + "\\bwithout (?:a )?prescription\\b.*\\b" + LAUNCH_CONTROLLED_SUBJECT + "\\b");
    private static final Pattern FORGED_OR_STOLEN_PRESCRIPTION = Pattern.compile(
            "\\b" + OPERATIONAL_REQUEST
                    + "\\s+(?:successfully )?(?:forge|steal|create a fake)\\b.*\\bprescription\\b");
    private static final Pattern UNLICENSED_MANUFACTURE = Pattern.compile(
            "\\b" + OPERATIONAL_REQUEST + "\\b.*\\b(?:make|manufacture|grow|cultivate)\\b"
                    + ".*\\b" + LAUNCH_CONTROLLED_SUBJECT + "\\b"
                    + ".*\\b(?:at home|without (?:being )?caught|evad(?:e|ing)|conceal(?:ing)?)\\b");
    private static final Pattern MONITORING_EVASION = Pattern.compile(
            "\\b" + OPERATIONAL_REQUEST + "\\b.*\\b(?:bypass|evade|avoid)\\b"
                    + ".*\\b(?:prescription\\s+)?monitoring\\b"
                    + ".*\\b(?:multiple|several)\\s+(?:doctors?|clinicians?)\\b"
                    + "|\\b" + OPERATIONAL_REQUEST + "\\b.*\\b(?:multiple|several)\\s+"
                    + "(?:doctors?|clinicians?)\\b.*\\b(?:prescription\\s+)?monitoring\\b"
                    + ".*\\b(?:bypass|evade|avoid)\\b"
                    + "|\\b" + OPERATIONAL_REQUEST + "\\b.*\\b(?:multiple|several)\\s+"
                    + "(?:doctors?|clinicians?)\\b.*\\bprescrib(?:e|ing)\\b"
                    + ".*\\bmonitoring\\b.*\\b(?:notic(?:e|ing)|bypass|evad(?:e|ing))\\b");
    private static final Pattern LICENSED_CARE = Pattern.compile(
            "\\b(?:discuss|ask|talk)\\b.*\\b(?:licensed )?(?:doctor|clinician|pharmacist)\\b");

    private final EmergencyTriage emergencyTriage;

    ResponsePlan plan(PlanningInput input, Supplier<ModelPlanAdvice> modelAdviceSupplier) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(modelAdviceSupplier, "modelAdviceSupplier");

        var emergency = emergencyTriage.screen(input.allUserText());
        if (emergency.isPresent()) {
            return emergencyPlan(emergency.orElseThrow());
        }

        String normalized = input.latestUserTurn().toLowerCase(Locale.ROOT);
        if (isExplicitControlEvasion(maskQuotedSpans(normalized))) {
            return new ResponsePlan(
                    ResponsePlan.ResponseMode.T5_REFUSE_ILLEGAL_ASSISTANCE,
                    Set.of(ResponsePlan.Capability.ILLEGAL_ASSISTANCE_REFUSAL),
                    ResponsePlan.ConfidenceBucket.HIGH,
                    Set.of(ResponsePlan.ReasonCode.EXPLICIT_CONTROL_EVASION),
                    null);
        }
        if (NEUTRAL_LEGAL_INFORMATION.matcher(normalized).find()) {
            return new ResponsePlan(
                    ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE,
                    Set.of(ResponsePlan.Capability.OFFICIAL_SOURCE_NAVIGATION),
                    ResponsePlan.ConfidenceBucket.HIGH,
                    Set.of(ResponsePlan.ReasonCode.NEUTRAL_LEGAL_INFORMATION),
                    null);
        }

        ResponsePlan.FacilityIntent facilityIntent = facilityIntent(normalized, input.facilityRuntime());
        if (facilityIntent != null && !NON_FACILITY_INTENT.matcher(normalized).find()) {
            return facilityOnlyPlan(facilityIntent);
        }

        ModelPlanAdvice advice;
        try {
            advice = modelAdviceSupplier.get();
        } catch (RuntimeException ignored) {
            advice = ModelPlanAdvice.none();
        }
        return adjudicate(
                advice == null ? ModelPlanAdvice.none() : advice, facilityIntent, normalized);
    }

    private static ResponsePlan adjudicate(
            ModelPlanAdvice advice,
            ResponsePlan.FacilityIntent facilityIntent,
            String normalizedUserTurn) {
        if (advice.suggestedMode() == ResponsePlan.ResponseMode.T4_EMERGENCY) {
            return emergencyPlan("MODEL_ESCALATION");
        }
        if (advice.suggestedMode() == ResponsePlan.ResponseMode.T5_REFUSE_ILLEGAL_ASSISTANCE
                && LICENSED_CARE.matcher(normalizedUserTurn).find()) {
            return consultationPlan(ResponsePlan.ConfidenceBucket.HIGH, facilityIntent);
        }
        if (advice.confidence() == ResponsePlan.ConfidenceBucket.LOW
                || advice.suggestedMode() == null
                || advice.suggestedMode() == ResponsePlan.ResponseMode.T5_REFUSE_ILLEGAL_ASSISTANCE) {
            return consultationFallback(advice.confidence(), facilityIntent);
        }

        ResponsePlan.ResponseMode mode = advice.suggestedMode();
        EnumSet<ResponsePlan.Capability> capabilities = EnumSet.noneOf(ResponsePlan.Capability.class);
        Set<ResponsePlan.ReasonCode> reasonCodes = switch (mode) {
            case T1_ANSWER_GENERAL_OR_LOCATE_CARE -> {
                copyAllowed(
                        advice.suggestedCapabilities(),
                        capabilities,
                        ResponsePlan.Capability.GENERAL_EXPLANATION,
                        ResponsePlan.Capability.OFFICIAL_MEDICINE_LOOKUP,
                        ResponsePlan.Capability.OFFICIAL_SOURCE_NAVIGATION);
                yield Set.of(ResponsePlan.ReasonCode.GENERAL_INFORMATION);
            }
            case T2_ANSWER_WITH_CONSULTATION -> {
                copyAllowed(
                        advice.suggestedCapabilities(),
                        capabilities,
                        ResponsePlan.Capability.GENERAL_EXPLANATION,
                        ResponsePlan.Capability.OFFICIAL_MEDICINE_LOOKUP,
                        ResponsePlan.Capability.OFFICIAL_SOURCE_NAVIGATION);
                capabilities.add(ResponsePlan.Capability.PROFESSIONAL_CONSULTATION);
                yield Set.of(ResponsePlan.ReasonCode.PROFESSIONAL_CONTEXT);
            }
            case T3_REFUSE_CLINICAL_AUTHORITY -> {
                capabilities.add(ResponsePlan.Capability.CLINICAL_REFUSAL);
                yield Set.of(ResponsePlan.ReasonCode.CLINICAL_AUTHORITY_REQUEST);
            }
            case T4_EMERGENCY, T5_REFUSE_ILLEGAL_ASSISTANCE -> throw new IllegalStateException(
                    "protected model advice must be adjudicated before capability copying");
        };

        if (facilityIntent != null) {
            capabilities.add(ResponsePlan.Capability.FACILITY_LOOKUP);
            EnumSet<ResponsePlan.ReasonCode> combined = EnumSet.copyOf(reasonCodes);
            combined.addAll(facilityReasonCodes(facilityIntent));
            reasonCodes = Set.copyOf(combined);
        }
        if (capabilities.isEmpty()) {
            return consultationFallback(advice.confidence(), facilityIntent);
        }
        return new ResponsePlan(
                mode,
                Set.copyOf(capabilities),
                advice.confidence(),
                reasonCodes,
                facilityIntent);
    }

    @SafeVarargs
    private static void copyAllowed(
            Set<ResponsePlan.Capability> advised,
            Set<ResponsePlan.Capability> target,
            ResponsePlan.Capability... allowed) {
        for (ResponsePlan.Capability capability : allowed) {
            if (advised.contains(capability)) {
                target.add(capability);
            }
        }
    }

    private static ResponsePlan consultationFallback(
            ResponsePlan.ConfidenceBucket confidence, ResponsePlan.FacilityIntent facilityIntent) {
        return consultationPlan(
                confidence == null ? ResponsePlan.ConfidenceBucket.LOW : confidence,
                facilityIntent,
                ResponsePlan.ReasonCode.LOW_CONFIDENCE);
    }

    private static ResponsePlan consultationPlan(
            ResponsePlan.ConfidenceBucket confidence, ResponsePlan.FacilityIntent facilityIntent) {
        return consultationPlan(
                confidence, facilityIntent, ResponsePlan.ReasonCode.PROFESSIONAL_CONTEXT);
    }

    private static ResponsePlan consultationPlan(
            ResponsePlan.ConfidenceBucket confidence,
            ResponsePlan.FacilityIntent facilityIntent,
            ResponsePlan.ReasonCode primaryReason) {
        EnumSet<ResponsePlan.Capability> capabilities =
                EnumSet.of(ResponsePlan.Capability.PROFESSIONAL_CONSULTATION);
        EnumSet<ResponsePlan.ReasonCode> reasonCodes =
                EnumSet.of(primaryReason);
        if (facilityIntent != null) {
            capabilities.add(ResponsePlan.Capability.FACILITY_LOOKUP);
            reasonCodes.addAll(facilityReasonCodes(facilityIntent));
        }
        return new ResponsePlan(
                ResponsePlan.ResponseMode.T2_ANSWER_WITH_CONSULTATION,
                Set.copyOf(capabilities),
                confidence,
                Set.copyOf(reasonCodes),
                facilityIntent);
    }

    private static ResponsePlan facilityOnlyPlan(ResponsePlan.FacilityIntent facilityIntent) {
        return new ResponsePlan(
                ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE,
                Set.of(ResponsePlan.Capability.FACILITY_LOOKUP),
                ResponsePlan.ConfidenceBucket.HIGH,
                facilityReasonCodes(facilityIntent),
                facilityIntent);
    }

    private static Set<ResponsePlan.ReasonCode> facilityReasonCodes(
            ResponsePlan.FacilityIntent facilityIntent) {
        EnumSet<ResponsePlan.ReasonCode> reasons =
                EnumSet.of(ResponsePlan.ReasonCode.FACILITY_REQUEST);
        if (facilityIntent.operationPreference() == FacilityOperationPreference.OPEN_OR_UNKNOWN) {
            reasons.add(ResponsePlan.ReasonCode.OPEN_NOW_REQUEST);
        }
        if (facilityIntent.availability() == ResponsePlan.FacilityAvailability.LOCATION_REQUIRED) {
            reasons.add(ResponsePlan.ReasonCode.LOCATION_UNAVAILABLE);
        }
        if (facilityIntent.availability() == ResponsePlan.FacilityAvailability.ADAPTER_UNAVAILABLE) {
            reasons.add(ResponsePlan.ReasonCode.FACILITY_ADAPTER_UNAVAILABLE);
        }
        return Set.copyOf(reasons);
    }

    private static ResponsePlan.FacilityIntent facilityIntent(
            String normalized, FacilityRuntime runtime) {
        if (CARE_DECISION.matcher(normalized).find()) {
            return null;
        }
        EnumSet<FacilityType> types = EnumSet.noneOf(FacilityType.class);
        if (PHARMACY.matcher(normalized).find()) {
            types.add(FacilityType.PHARMACY);
        }
        boolean emergencyRoom = EMERGENCY_ROOM.matcher(normalized).find();
        if (emergencyRoom) {
            types.add(FacilityType.EMERGENCY_ROOM);
        }
        String hospitalTargetText = HOSPITAL_EMERGENCY_ROOM.matcher(normalized).replaceAll(" ");
        if (types.contains(FacilityType.PHARMACY)) {
            hospitalTargetText = HOSPITAL_LANDMARK.matcher(hospitalTargetText).replaceAll(" ");
        }
        if (HOSPITAL.matcher(hospitalTargetText).find()) {
            types.add(FacilityType.HOSPITAL);
        }
        boolean openNow = OPEN_SIGNAL.matcher(normalized).find();
        if (types.size() != 1 || (!LOCATION_SIGNAL.matcher(normalized).find() && !openNow)) {
            return null;
        }

        ResponsePlan.FacilityAvailability availability;
        if (!runtime.locationAvailable()) {
            availability = ResponsePlan.FacilityAvailability.LOCATION_REQUIRED;
        } else if (!runtime.deployedTypes().containsAll(types)) {
            availability = ResponsePlan.FacilityAvailability.ADAPTER_UNAVAILABLE;
        } else {
            availability = ResponsePlan.FacilityAvailability.READY;
        }
        return new ResponsePlan.FacilityIntent(
                Set.copyOf(types),
                openNow
                        ? FacilityOperationPreference.OPEN_OR_UNKNOWN
                        : FacilityOperationPreference.ANY,
                availability);
    }

    private static boolean isExplicitControlEvasion(String normalized) {
        return BLACK_MARKET.matcher(normalized).find()
                || WITHOUT_PRESCRIPTION.matcher(normalized).find()
                || FORGED_OR_STOLEN_PRESCRIPTION.matcher(normalized).find()
                || UNLICENSED_MANUFACTURE.matcher(normalized).find()
                || MONITORING_EVASION.matcher(normalized).find();
    }

    private static String maskQuotedSpans(String normalized) {
        return QUOTED_SPAN.matcher(normalized).replaceAll(" ");
    }

    private static ResponsePlan emergencyPlan(String reasonCode) {
        return new ResponsePlan(
                ResponsePlan.ResponseMode.T4_EMERGENCY,
                Set.of(ResponsePlan.Capability.EMERGENCY_RESPONSE),
                ResponsePlan.ConfidenceBucket.HIGH,
                Set.of(ResponsePlan.ReasonCode.EMERGENCY_RED_FLAG),
                null,
                new ResponsePlan.EmergencyDecision(reasonCode));
    }

    record PlanningInput(String latestUserTurn, String allUserText, FacilityRuntime facilityRuntime) {
        PlanningInput {
            Objects.requireNonNull(latestUserTurn, "latestUserTurn");
            Objects.requireNonNull(allUserText, "allUserText");
            Objects.requireNonNull(facilityRuntime, "facilityRuntime");
        }
    }

    record FacilityRuntime(boolean locationAvailable, Set<FacilityType> deployedTypes) {
        FacilityRuntime {
            Objects.requireNonNull(deployedTypes, "deployedTypes");
            deployedTypes = Set.copyOf(deployedTypes);
        }

        static FacilityRuntime allAvailable() {
            return new FacilityRuntime(true, Set.of(FacilityType.values()));
        }
    }
}
