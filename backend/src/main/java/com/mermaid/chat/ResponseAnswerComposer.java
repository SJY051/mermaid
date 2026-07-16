package com.mermaid.chat;

import com.mermaid.chat.DrugContextRetriever.DrugContext;
import com.mermaid.chat.GeneralExplanationPolicy.ValidatedGeneralExplanation;
import com.mermaid.chat.dto.MermAidAnswer;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Composes successful capability results without letting one failed capability erase another. */
@Component
final class ResponseAnswerComposer {

    private final ServerAuthoredAnswerBuilder medicineAnswerBuilder;

    ResponseAnswerComposer(ServerAuthoredAnswerBuilder medicineAnswerBuilder) {
        this.medicineAnswerBuilder = Objects.requireNonNull(medicineAnswerBuilder);
    }

    MermAidAnswer compose(ResponsePlan plan, CapabilityResults results) {
        // TODO DEV-603 / spec 011: protected T3/T4/T5 copy, actions, medicine cards, provenance,
        // allergy state, urgency, answer IDs and disclaimer are server-owned. Never merge arbitrary
        // model MermAidAnswer objects here.
        throw new UnsupportedOperationException("Response answer composer scaffold is not implemented");
    }

    /**
     * Results from independently executed capabilities.
     *
     * <p>The medicine answer is the canonical server-authored answer, never a whole-answer model
     * response. Nullable fields mean that capability produced no usable result; {@code failures}
     * preserves why without carrying user or provider text.
     */
    record CapabilityResults(
            ValidatedGeneralExplanation generalExplanation,
            DrugContext medicineContext,
            Set<CapabilityFailure> failures) {

        CapabilityResults {
            Objects.requireNonNull(failures, "failures");
            failures = Set.copyOf(failures);
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
