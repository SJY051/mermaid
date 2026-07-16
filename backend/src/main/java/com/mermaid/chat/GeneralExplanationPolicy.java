package com.mermaid.chat;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Converts untrusted T1/T2 prose into an opaque value only after output-policy validation. */
@Component
final class GeneralExplanationPolicy {

    ValidationResult validate(String candidate) {
        // TODO DEV-603 / spec 011: reject diagnosis, personal dose/treatment/prescription decisions,
        // cure/safe claims, unverified medicine facts, official-data claims and unsafe markup.
        throw new UnsupportedOperationException("General explanation policy scaffold is not implemented");
    }

    static final class ValidatedGeneralExplanation {
        private final String summary;

        private ValidatedGeneralExplanation(String summary) {
            this.summary = Objects.requireNonNull(summary, "summary");
        }

        String summary() {
            return summary;
        }
    }

    static final class ValidationResult {
        private final ValidatedGeneralExplanation explanation;
        private final Set<ViolationCode> violations;

        private ValidationResult(
                ValidatedGeneralExplanation explanation, Set<ViolationCode> violations) {
            this.explanation = explanation;
            this.violations = Set.copyOf(violations);
        }

        Optional<ValidatedGeneralExplanation> explanation() {
            return Optional.ofNullable(explanation);
        }

        Set<ViolationCode> violations() {
            return violations;
        }
    }

    enum ViolationCode {
        DEFINITE_DIAGNOSIS,
        PERSONAL_DOSE,
        TREATMENT_SELECTION,
        PRESCRIPTION_DECISION,
        CURE_CLAIM,
        SAFE_CLAIM,
        UNVERIFIED_MEDICINE_FACT,
        OFFICIAL_DATA_CLAIM,
        UNSAFE_MARKUP
    }
}
