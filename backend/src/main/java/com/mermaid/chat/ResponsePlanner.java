package com.mermaid.chat;

import com.mermaid.facility.domain.FacilityType;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Adjudicates deterministic safety rules and optional model advice into a server-owned plan. */
@Component
@RequiredArgsConstructor
final class ResponsePlanner {

    private final EmergencyTriage emergencyTriage;

    ResponsePlan plan(PlanningInput input, Supplier<ModelPlanAdvice> modelAdviceSupplier) {
        // TODO DEV-603 / spec 011: T4 first; deterministic facility and bounded T5 next; only then
        // invoke the lazy adviser for advisory T1/T2/T3. Low confidence must clarify or choose T2.
        throw new UnsupportedOperationException("Response planner scaffold is not implemented");
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
