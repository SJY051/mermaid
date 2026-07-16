package com.mermaid.chat;

import java.util.Objects;
import java.util.Set;

/**
 * Untrusted model advice for the response planner.
 *
 * <p>This shape deliberately cannot carry prose, medicine facts, urgency copy, UI actions, source
 * references, answer IDs, allergy decisions, or disclaimers.
 */
record ModelPlanAdvice(
        ResponsePlan.ResponseMode suggestedMode,
        Set<ResponsePlan.Capability> suggestedCapabilities,
        ResponsePlan.ConfidenceBucket confidence,
        Set<ResponsePlan.ReasonCode> reasonCodes) {

    ModelPlanAdvice {
        Objects.requireNonNull(suggestedCapabilities, "suggestedCapabilities");
        Objects.requireNonNull(confidence, "confidence");
        Objects.requireNonNull(reasonCodes, "reasonCodes");
        suggestedCapabilities = Set.copyOf(suggestedCapabilities);
        reasonCodes = Set.copyOf(reasonCodes);
    }

    static ModelPlanAdvice none() {
        return new ModelPlanAdvice(
                null,
                Set.of(),
                ResponsePlan.ConfidenceBucket.LOW,
                Set.of(ResponsePlan.ReasonCode.LOW_CONFIDENCE));
    }
}
