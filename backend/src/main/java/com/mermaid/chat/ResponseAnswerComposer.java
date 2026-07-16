package com.mermaid.chat;

import com.mermaid.chat.DrugContextRetriever.DrugContext;
import com.mermaid.chat.GeneralExplanationPipeline.RenderedGeneralExplanation;
import com.mermaid.chat.dto.MermAidAnswer;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Composes successful capability results without letting one failed capability erase another. */
@Component
final class ResponseAnswerComposer {

    private final ServerAuthoredAnswerBuilder medicineAnswerBuilder;
    private final EmergencyTriage emergencyTriage;

    ResponseAnswerComposer(
            ServerAuthoredAnswerBuilder medicineAnswerBuilder,
            EmergencyTriage emergencyTriage) {
        this.medicineAnswerBuilder = Objects.requireNonNull(medicineAnswerBuilder);
        this.emergencyTriage = Objects.requireNonNull(emergencyTriage);
    }

    MermAidAnswer compose(ResponsePlan plan, CapabilityResults results) {
        // DEV-603 RED scaffold. Protected copy, actions, medicine cards, provenance, allergy state,
        // urgency, answer IDs and disclaimer remain server-owned when this is implemented.
        throw new UnsupportedOperationException("Response answer composer scaffold is not implemented");
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
