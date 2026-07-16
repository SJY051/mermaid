package com.mermaid.chat;

import com.mermaid.chat.ModelGeneralExplanationDraft.ComparisonDimension;
import com.mermaid.chat.ModelGeneralExplanationDraft.DefinitionPredicate;
import com.mermaid.chat.ModelGeneralExplanationDraft.GeneralConcept;
import com.mermaid.chat.ModelGeneralExplanationDraft.GeneralSubject;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Reviews normalized claims only; it cannot return replacement prose or edit a claim. */
@FunctionalInterface
interface GeneralExplanationSemanticVerifier {

    SemanticReviewDecision review(SemanticReviewInput input);

    /** Safe review view: server-owned subject, predicate, and concept identifiers only. */
    record SemanticReviewInput(List<SemanticClaim> claims) {

        public SemanticReviewInput {
            Objects.requireNonNull(claims, "claims");
            claims = List.copyOf(claims);
        }
    }

    sealed interface SemanticClaim
            permits SymptomPossibilityReview, TermDefinitionReview, GeneralComparisonReview {}

    record SymptomPossibilityReview(
            GeneralSubject subject, List<GeneralConcept> possibleCauses)
            implements SemanticClaim {

        public SymptomPossibilityReview {
            Objects.requireNonNull(subject, "subject");
            possibleCauses = List.copyOf(possibleCauses);
        }
    }

    record TermDefinitionReview(
            GeneralSubject subject,
            DefinitionPredicate predicate,
            List<GeneralConcept> concepts)
            implements SemanticClaim {

        public TermDefinitionReview {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(predicate, "predicate");
            concepts = List.copyOf(concepts);
        }
    }

    record GeneralComparisonReview(
            GeneralSubject leftSubject,
            GeneralSubject rightSubject,
            ComparisonDimension dimension,
            List<GeneralConcept> leftConcepts,
            List<GeneralConcept> rightConcepts)
            implements SemanticClaim {

        public GeneralComparisonReview {
            Objects.requireNonNull(leftSubject, "leftSubject");
            Objects.requireNonNull(rightSubject, "rightSubject");
            Objects.requireNonNull(dimension, "dimension");
            leftConcepts = List.copyOf(leftConcepts);
            rightConcepts = List.copyOf(rightConcepts);
        }
    }

    record SemanticReviewDecision(
            Verdict verdict, Set<SemanticViolation> violations, ConfidenceBucket confidence) {

        public SemanticReviewDecision {
            Objects.requireNonNull(verdict, "verdict");
            Objects.requireNonNull(violations, "violations");
            Objects.requireNonNull(confidence, "confidence");
            violations = Set.copyOf(violations);
        }

        boolean allowsRendering() {
            return verdict == Verdict.ALLOW
                    && confidence == ConfidenceBucket.HIGH
                    && violations.isEmpty();
        }
    }

    enum Verdict {
        ALLOW,
        REJECT
    }

    enum ConfidenceBucket {
        HIGH,
        MEDIUM,
        LOW
    }

    enum SemanticViolation {
        PERSONAL_DIAGNOSIS,
        MEDICINE_OR_TREATMENT_FACT,
        DOSE_OR_SCHEDULE,
        PRESCRIPTION_DECISION,
        CURE_OR_SAFETY_CLAIM,
        OFFICIAL_DATA_CLAIM,
        SELF_CARE_REGIMEN,
        DURATION_THRESHOLD,
        DISEASE_SPECIFIC_TREATMENT,
        PERSONAL_RISK_SCORE,
        NEW_EMERGENCY_GUIDANCE,
        FACTUALLY_IMPLAUSIBLE,
        IRRELEVANT_TO_REQUEST
    }

    final class SemanticReviewUnavailableException extends RuntimeException {
        SemanticReviewUnavailableException() {
            super("semantic review unavailable");
        }
    }
}
