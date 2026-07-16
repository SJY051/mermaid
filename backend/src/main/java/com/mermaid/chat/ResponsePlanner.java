package com.mermaid.chat;

import com.mermaid.config.RiskTierFeatureProperties;
import com.mermaid.facility.domain.FacilityOperationPreference;
import com.mermaid.facility.domain.FacilityType;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Adjudicates deterministic safety rules and optional model advice into a server-owned plan. */
@Component
@RequiredArgsConstructor
final class ResponsePlanner {

    private static final Pattern EMERGENCY_ROOM =
            Pattern.compile("\\b(?:ers?|emergency rooms?|emergency departments?)\\b");
    private static final Pattern HOSPITAL_EMERGENCY_ROOM = Pattern.compile(
            "\\bhospitals?(?:['’]s)?\\s+(?:ers?|emergency rooms?|emergency departments?)\\b");
    private static final String ER_TARGET_TOKEN = "emergencyroomtarget";
    private static final Pattern FACILITY_MENTION = Pattern.compile(
            "\\b(?:pharmac(?:y|ies)|drugstores?|hospitals?|" + ER_TARGET_TOKEN + ")\\b");
    private static final Pattern LANDMARK_RELATION = Pattern.compile(
            "\\b(?:near|beside|by|around|at|in|inside|next to|close to|closest to|"
                    + "across(?: the street)? from)\\b");
    private static final Pattern LANDMARK_TARGET_BREAK = Pattern.compile(
            "\\b(?:and|or|where|find|locate|show|look|me|my|us|our|here|current)\\b");
    private static final Pattern LOCATION_SIGNAL = Pattern.compile(
            "\\b(?:closest|directions?|find|locate|map|nearest|nearby|where)\\b"
                    + "|\\blook\\s+for\\b|\\b(?:near|around)\\s+(?:me|my current location)\\b");
    private static final Pattern OPEN_SIGNAL = Pattern.compile(
            "\\bopen\\b(?:\\s+\\w+){0,3}\\s+\\b(?:now|currently|today|tonight|moment|minute|present|presently|instant)\\b"
                    + "|\\bopened\\b(?:\\s+\\w+){0,2}\\s+\\b(?:right\\s+now|currently|present|instant)\\b"
                    + "|\\b(?:now|currently|today|tonight|moment|minute|present|presently|instant)\\b"
                    + "(?:\\s+\\w+){0,3}\\s+\\bopen\\b"
                    + "|\\b(?:currently|presently|still)\\s+open(?:ed)?\\b"
                    + "|\\boperating\\s+(?:right\\s+now|currently|at\\s+present)\\b"
                    + "|\\bopen\\s+as\\s+we\\s+speak\\b"
                    + "|\\bopen\\s+at\\s+this\\s+hour\\b"
                    + "|\\b24[ -]?hours?\\b|\\b24\\s*/\\s*7\\b");
    private static final Pattern OPEN_BEFORE_FACILITY_BRIDGE =
            Pattern.compile("\\s*(?:(?:nearby|nearest|closest)\\s+)?");
    private static final Pattern FACILITY_BEFORE_OPEN_BRIDGE = Pattern.compile(
            "\\s*(?:(?:near|around)\\s+(?:me|my current location)\\s*)?"
                    + "(?:(?:(?:that|which)\\s+(?:is|are)|that['’]s|(?:is|are))\\s*)?");
    private static final Pattern NON_FACILITY_INTENT = Pattern.compile(
            "\\b(?:allerg(?:y|ic|ies)|cold|cough|dose|fever|headache|interaction|medicine|"
                    + "medication|pain|sick|sore throat|symptoms?|tablet|treatment)\\b");
    private static final String CARE_SUBJECT = "(?:i|we|you|he|she|they|someone|this person"
            + "|(?:my|our|their|his|her) "
            + "(?:child|baby|son|daughter|mother|father|parent|partner|friend)"
            + "|(?:the|a) patient)";
    private static final Pattern CARE_DECISION = Pattern.compile(
            "\\b(?:should|ought(?:\\s+to)?)\\s+" + CARE_SUBJECT
                    + "\\s+(?:go|visit|get\\s+checked|be\\s+(?:taken|brought))\\b"
                    + "|\\b" + CARE_SUBJECT
                    + "\\s+(?:should|ought\\s+to|needs?\\s+to|must|has\\s+to)"
                    + "\\s+(?:go|visit|get\\s+checked|be\\s+(?:taken|brought))\\b"
                    + "|\\b(?:do|does)\\s+" + CARE_SUBJECT
                    + "\\s+need\\s+to\\s+(?:go|visit)\\b"
                    + "|\\b" + CARE_SUBJECT + "\\s+(?:requires?|needs?)\\s+(?:a\\s+|the\\s+)?hospital\\b"
                    + "|\\b(?:a\\s+)?hospital\\s+(?:would|could|might)\\s+be"
                    + "\\s+(?:appropriate|necessary|suitable)\\s+for\\b"
                    + "|\\bhospital\\s+care\\s+(?:is|(?:would|could|might)\\s+be)"
                    + "\\s+(?:appropriate|necessary|suitable)\\s+for\\b"
                    + "|\\bsigns? (?:that )?i need\\b.*\\b(?:hospital|ers?|emergency rooms?)\\b");

    private static final String NEUTRAL_LEGAL_TOPIC =
            "(?:prescribed legally|legal(?:ly)? prescribed|medical narcotics?)";
    private static final Pattern NEUTRAL_LEGAL_INFORMATION = Pattern.compile(
            "\\b" + NEUTRAL_LEGAL_TOPIC + "\\b.*\\b(?:korea|korean law|law)\\b"
                    + "|\\b(?:korea|korean law|law)\\b.*\\b"
                    + NEUTRAL_LEGAL_TOPIC
                    + "\\b");
    private static final Pattern NEUTRAL_LEGAL_TERMINOLOGY = Pattern.compile(
            "\\bwhat\\s+does\\s+(?:a\\s+)?[\\\"'‘’]?medical\\s+narcotics?"
                    + "[\\\"'‘’]?\\s+mean\\b");
    private static final Pattern QUOTED_SPAN = Pattern.compile(
            "\\\"(?:[^\\\"\\\\]|\\\\.)*\\\""
                    + "|(?<![\\p{L}\\p{N}])'(?:[^'\\\\]|\\\\.)*'(?![\\p{L}\\p{N}])"
                    + "|`[^`]*`|“[^”]*”|‘[^’]*’");
    private static final Pattern NON_DRUG_CONTROLLED_CONTEXT = Pattern.compile(
            "\\bfentanyl(?:[-\\s]+test(?:[-\\s]+(?:strips?|kits?))?"
                    + "|[-\\s]+testing[-\\s]+kits?"
                    + "|[-\\s]+detection[-\\s]+(?:cards?|strips?|kits?))\\b"
                    + "|\\b(?:books?|articles?|reports?|documentaries)\\s+(?:about|on)\\s+fentanyl\\b");
    private static final Pattern OPERATIONAL_GUIDANCE = Pattern.compile(
            "\\b(?:how\\s+(?:can|could|do|would)|where\\s+(?:can|do)"
                    + "|(?:explain|tell me|show me|teach me)\\s+how|instructions?"
                    + "|step-by-step|steps?\\s+(?:for|to)|best\\s+way"
                    + "|(?:help|can you help)\\s+me|give me (?:a )?guide)\\b");
    private static final Pattern PROTECTIVE_GUIDANCE = Pattern.compile(
            "\\b(?:how\\s+(?:can|could|do)|(?:explain|show|tell) me how(?: to)?)\\s+"
                    + "(?:(?:i|we|someone)\\s+)?(?:avoid|report|stop|prevent|detect|identify)\\b");
    private static final Pattern PROTECTIVE_CONTINUATION =
            Pattern.compile("\\b(?:and|but|then|while|when)\\b");
    private static final Pattern CONTROLLED_SUBJECT = Pattern.compile(
            "\\b(?:fentanyl|controlled\\s+drugs?|medical\\s+narcotics?|narcotics?|illicit\\s+drugs?)\\b");
    private static final Pattern ACQUISITION_ACTION =
            Pattern.compile("\\b(?:acquir(?:e|ed|ing)|buy(?:ing)?|sell(?:ing)?|obtain(?:ing)?|"
                    + "get(?:ting)?|procur(?:e|ed|ing)|purchas(?:e|ed|ing)|sourc(?:e|ed|ing))\\b");
    private static final Pattern BLACK_MARKET = Pattern.compile("\\bblack\\s+market\\b");
    private static final Pattern WITHOUT_PRESCRIPTION =
            Pattern.compile("\\bwithout\\s+(?:a\\s+)?prescription\\b");
    private static final Pattern DIRECT_PRESCRIPTION_FORGERY = Pattern.compile(
            "\\b(?:forge|forging|steal|stealing)\\b.*\\bprescriptions?\\b");
    private static final Pattern CREATE_FAKE_PRESCRIPTION = Pattern.compile(
            "\\b(?:create|creating|make|making)\\b.*\\b(?:fake|forged)\\s+prescriptions?\\b");
    private static final Pattern MANUFACTURE_ACTION =
            Pattern.compile("\\b(?:make|making|manufacture|manufacturing|produce|producing|"
                    + "compound(?:ed|ing)?|synthesiz(?:e|ed|ing)|grow|growing|"
                    + "cultivate|cultivating)\\b");
    private static final Pattern MANUFACTURE_EVASION = Pattern.compile(
            "\\b(?:at\\s+home|without\\s+(?:being\\s+)?caught|evad(?:e|ing)|conceal(?:ing)?)\\b");
    private static final Pattern MULTIPLE_PRESCRIBERS = Pattern.compile(
            "\\b(?:multiple|several)\\s+(?:doctors?|clinicians?)\\b"
                    + "|\\bprescriptions?\\s+from\\s+(?:multiple|several)\\s+(?:doctors?|clinicians?)\\b");
    private static final Pattern MONITORING = Pattern.compile("\\bmonitoring\\b");
    private static final Pattern MONITORING_EVASION = Pattern.compile(
            "\\b(?:bypass|evade|avoid|conceal|hide)\\b(?:\\s+\\w+){0,3}\\s+\\bmonitoring\\b"
                    + "|\\bwithout\\s+monitoring\\b"
                    + "|\\bmonitoring\\b(?:\\s+\\w+){0,3}\\s+\\b(?:unnoticed|not\\s+notic(?:e|ing))\\b");
    private static final Pattern PREVENT_MONITORING_DETECTION = Pattern.compile(
            "\\bprevent\\s+(?:prescription\\s+)?monitoring\\s+from\\s+(?:detecting|noticing)\\b");
    private static final Pattern CLAUSE_BOUNDARY =
            Pattern.compile("[.!?;]+|\\R+|,\\s*(?:and|but)\\s+|"
                    + ":\\s*(?=(?:is|are|can|could|should|would|where|how|what|who|why|"
                    + "i|we|you|he|she|they)\\b)|"
                    + "\\band\\s+(?=(?:is|are|can|could|should|would|where|how|what|who|why|"
                    + "i|we|you|he|she|they)\\b)|\\bhowever\\b");
    private static final Pattern LICENSED_CARE = Pattern.compile(
            "\\b(?:discuss|ask|talk)\\b.*\\b(?:licensed )?(?:doctor|clinician|pharmacist)\\b");

    private final EmergencyTriage emergencyTriage;
    private final RiskTierFeatureProperties features;

    ResponsePlan plan(PlanningInput input, Supplier<ModelPlanAdvice> modelAdviceSupplier) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(modelAdviceSupplier, "modelAdviceSupplier");

        var emergency = emergencyTriage.screen(input.allUserText());
        if (emergency.isPresent()) {
            return emergencyPlan(emergency.orElseThrow());
        }

        String normalized = input.latestUserTurn().toLowerCase(Locale.ROOT);
        if (isExplicitControlEvasion(normalized)) {
            if (features.t5PolicyEnabled()) {
                return new ResponsePlan(
                        ResponsePlan.ResponseMode.T5_REFUSE_ILLEGAL_ASSISTANCE,
                        Set.of(ResponsePlan.Capability.ILLEGAL_ASSISTANCE_REFUSAL),
                        ResponsePlan.ConfidenceBucket.HIGH,
                        Set.of(ResponsePlan.ReasonCode.EXPLICIT_CONTROL_EVASION),
                        null);
            }
            return consultationPlan(ResponsePlan.ConfidenceBucket.HIGH, null);
        }
        if (features.t5PolicyEnabled()
                && (NEUTRAL_LEGAL_INFORMATION.matcher(normalized).find()
                        || NEUTRAL_LEGAL_TERMINOLOGY.matcher(normalized).find())) {
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
        if (!features.ambiguousPlanningEnabled()) {
            return legacyMedicinePlan(facilityIntent);
        }

        ModelPlanAdvice advice;
        try {
            advice = modelAdviceSupplier.get();
        } catch (RuntimeException ignored) {
            advice = ModelPlanAdvice.none();
        }
        return adjudicate(
                advice == null ? ModelPlanAdvice.none() : advice,
                facilityIntent,
                normalized,
                features.generalExplanationEnabled());
    }

    private static ResponsePlan adjudicate(
            ModelPlanAdvice advice,
            ResponsePlan.FacilityIntent facilityIntent,
            String normalizedUserTurn,
            boolean generalExplanationEnabled) {
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
                        ResponsePlan.Capability.OFFICIAL_MEDICINE_LOOKUP,
                        ResponsePlan.Capability.OFFICIAL_SOURCE_NAVIGATION);
                copyGeneralExplanation(
                        advice.suggestedCapabilities(), capabilities, generalExplanationEnabled);
                yield Set.of(ResponsePlan.ReasonCode.GENERAL_INFORMATION);
            }
            case T2_ANSWER_WITH_CONSULTATION -> {
                copyAllowed(
                        advice.suggestedCapabilities(),
                        capabilities,
                        ResponsePlan.Capability.OFFICIAL_MEDICINE_LOOKUP,
                        ResponsePlan.Capability.OFFICIAL_SOURCE_NAVIGATION);
                copyGeneralExplanation(
                        advice.suggestedCapabilities(), capabilities, generalExplanationEnabled);
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

    private static void copyGeneralExplanation(
            Set<ResponsePlan.Capability> advised,
            Set<ResponsePlan.Capability> target,
            boolean enabled) {
        if (enabled && advised.contains(ResponsePlan.Capability.GENERAL_EXPLANATION)) {
            target.add(ResponsePlan.Capability.GENERAL_EXPLANATION);
        }
    }

    private static ResponsePlan consultationFallback(
            ResponsePlan.ConfidenceBucket confidence, ResponsePlan.FacilityIntent facilityIntent) {
        return consultationPlan(
                confidence == null ? ResponsePlan.ConfidenceBucket.LOW : confidence,
                facilityIntent,
                ResponsePlan.ReasonCode.LOW_CONFIDENCE);
    }

    private static ResponsePlan legacyMedicinePlan(
            ResponsePlan.FacilityIntent facilityIntent) {
        EnumSet<ResponsePlan.Capability> capabilities = EnumSet.of(
                ResponsePlan.Capability.OFFICIAL_MEDICINE_LOOKUP,
                ResponsePlan.Capability.PROFESSIONAL_CONSULTATION);
        EnumSet<ResponsePlan.ReasonCode> reasonCodes =
                EnumSet.of(ResponsePlan.ReasonCode.LOW_CONFIDENCE);
        if (facilityIntent != null) {
            capabilities.add(ResponsePlan.Capability.FACILITY_LOOKUP);
            reasonCodes.addAll(facilityReasonCodes(facilityIntent));
        }
        return new ResponsePlan(
                ResponsePlan.ResponseMode.T2_ANSWER_WITH_CONSULTATION,
                Set.copyOf(capabilities),
                ResponsePlan.ConfidenceBucket.LOW,
                Set.copyOf(reasonCodes),
                facilityIntent);
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

        List<FacilityMention> mentions = facilityMentions(normalized);
        EnumSet<FacilityType> types = EnumSet.noneOf(FacilityType.class);
        List<FacilityMention> targetMentions = new ArrayList<>();
        for (int index = 0; index < mentions.size(); index++) {
            FacilityMention current = mentions.get(index);
            int previousEnd = index == 0 ? 0 : mentions.get(index - 1).end();
            String before = current.normalizedText().substring(previousEnd, current.start());
            if (!isLandmarkMention(before)) {
                types.add(current.type());
                targetMentions.add(current);
            }
        }
        boolean openNow = isOpenNowRequest(targetMentions);
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

    private static boolean isLandmarkMention(String beforeMention) {
        Matcher relation = LANDMARK_RELATION.matcher(beforeMention);
        int relationEnd = -1;
        while (relation.find()) {
            relationEnd = relation.end();
        }
        if (relationEnd < 0) {
            return false;
        }
        String relationTail = beforeMention.substring(relationEnd).trim();
        return !LANDMARK_TARGET_BREAK.matcher(relationTail).find();
    }

    private static boolean isOpenNowRequest(List<FacilityMention> targetMentions) {
        for (FacilityMention target : targetMentions) {
            Matcher openSignal = OPEN_SIGNAL.matcher(target.normalizedText());
            while (openSignal.find()) {
                if (openSignal.end() <= target.start()) {
                    String bridge = target.normalizedText()
                            .substring(openSignal.end(), target.start());
                    if (OPEN_BEFORE_FACILITY_BRIDGE.matcher(bridge).matches()) {
                        return true;
                    }
                } else if (openSignal.start() >= target.end()) {
                    String bridge = target.normalizedText()
                            .substring(target.end(), openSignal.start());
                    if (FACILITY_BEFORE_OPEN_BRIDGE.matcher(bridge).matches()) {
                        return true;
                    }
                } else if (openSignal.start() <= target.start()
                        && openSignal.end() >= target.end()) {
                    String overlap = target.normalizedText()
                            .substring(openSignal.start(), openSignal.end());
                    if (!overlap.matches(".*[.!?;,].*")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private static boolean isExplicitControlEvasion(String normalized) {
        String policyText = NON_DRUG_CONTROLLED_CONTEXT
                .matcher(maskQuotedSpans(normalized))
                .replaceAll(" ");
        for (String clause : CLAUSE_BOUNDARY.split(policyText)) {
            if (isExplicitControlEvasionClause(clause)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isExplicitControlEvasionClause(String clause) {
        if (!OPERATIONAL_GUIDANCE.matcher(clause).find()) {
            return false;
        }

        Matcher protective = PROTECTIVE_GUIDANCE.matcher(clause);
        if (protective.find()) {
            if (MULTIPLE_PRESCRIBERS.matcher(clause).find()
                    && PREVENT_MONITORING_DETECTION.matcher(clause).find()) {
                return true;
            }
            Matcher continuation = PROTECTIVE_CONTINUATION.matcher(clause);
            if (continuation.find(protective.end())) {
                do {
                    if (hasExplicitControlEvasionOperation(
                            clause.substring(continuation.end()))) {
                        return true;
                    }
                } while (continuation.find());
            }
            return false;
        }

        return hasExplicitControlEvasionOperation(clause);
    }

    private static boolean hasExplicitControlEvasionOperation(String clause) {
        boolean acquisitionEvasion = ACQUISITION_ACTION.matcher(clause).find()
                && CONTROLLED_SUBJECT.matcher(clause).find()
                && (BLACK_MARKET.matcher(clause).find()
                        || WITHOUT_PRESCRIPTION.matcher(clause).find());
        boolean prescriptionForgery = DIRECT_PRESCRIPTION_FORGERY.matcher(clause).find()
                || CREATE_FAKE_PRESCRIPTION.matcher(clause).find();
        boolean unlicensedManufacture = MANUFACTURE_ACTION.matcher(clause).find()
                && CONTROLLED_SUBJECT.matcher(clause).find()
                && MANUFACTURE_EVASION.matcher(clause).find();
        boolean monitoringEvasion = MULTIPLE_PRESCRIBERS.matcher(clause).find()
                && MONITORING.matcher(clause).find()
                && MONITORING_EVASION.matcher(clause).find();
        return acquisitionEvasion
                || prescriptionForgery
                || unlicensedManufacture
                || monitoringEvasion;
    }

    private static String maskQuotedSpans(String normalized) {
        return QUOTED_SPAN.matcher(normalized).replaceAll(" ");
    }

    private static List<FacilityMention> facilityMentions(String text) {
        String normalizedText = HOSPITAL_EMERGENCY_ROOM
                .matcher(text)
                .replaceAll(" " + ER_TARGET_TOKEN + " ");
        normalizedText = EMERGENCY_ROOM
                .matcher(normalizedText)
                .replaceAll(" " + ER_TARGET_TOKEN + " ");

        List<FacilityMention> mentions = new ArrayList<>();
        Matcher matcher = FACILITY_MENTION.matcher(normalizedText);
        while (matcher.find()) {
            String value = matcher.group();
            FacilityType type;
            if (value.equals(ER_TARGET_TOKEN)) {
                type = FacilityType.EMERGENCY_ROOM;
            } else if (value.startsWith("pharmac") || value.startsWith("drugstore")) {
                type = FacilityType.PHARMACY;
            } else {
                type = FacilityType.HOSPITAL;
            }
            mentions.add(new FacilityMention(type, matcher.start(), matcher.end(), normalizedText));
        }
        return List.copyOf(mentions);
    }

    private record FacilityMention(
            FacilityType type, int start, int end, String normalizedText) {}

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
