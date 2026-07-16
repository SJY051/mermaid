package com.mermaid.chat;

import java.text.Normalizer;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Converts untrusted T1/T2 prose into an opaque value only after output-policy validation. */
@Component
final class GeneralExplanationPolicy {

    private static final int MAX_LENGTH = 1_200;
    private static final Pattern DEFINITE_DIAGNOSIS = Pattern.compile(
            "(?i)\\b(?:you (?:definitely |probably |likely )?(?:have|suffer from)"
                    + "|your symptoms? (?:mean|means|prove|proves|confirm|confirms|show|shows)"
                    + "(?: that)? you have|this (?:proves|confirms|means) (?:that )?you have)\\b");
    private static final Pattern PERSONAL_DOSE = Pattern.compile(
            "(?i)(?:\\bbased on your (?:age|weight|symptoms?)\\b.*\\b(?:take|use|give)\\b"
                    + "|\\b(?:take|use|give)\\s+(?:\\d+|one|two|three|four|half)\\s+"
                    + "(?:tablets?|capsules?|doses?|mg|ml)\\b"
                    + "|\\bevery\\s+(?:\\d+|one|two|three|four|six|eight|twelve)\\s+hours?\\b)");
    private static final Pattern TREATMENT_SELECTION = Pattern.compile(
            "(?i)\\b(?:choose|select) the (?:treatment|therapy|remedy)\\b"
                    + "|\\b(?:treatment|therapy|remedy) (?:you|i) should (?:use|take)\\b");
    private static final Pattern PRESCRIPTION_DECISION = Pattern.compile(
            "(?i)\\b(?:decide|determine|tell me)\\b.*\\b(?:need|should (?:take|use))\\b"
                    + ".*\\bprescription\\b");
    private static final Pattern CURE_CLAIM = Pattern.compile(
            "(?i)\\b(?:will|can|guaranteed to) cure\\b|\\bcures? your\\b");
    private static final Pattern SAFE_CLAIM = Pattern.compile(
            "(?i)\\b(?:completely |perfectly |generally )?safe (?:for you|to (?:take|use))\\b"
                    + "|\\bno risk (?:for you|in taking|in using)\\b");
    private static final Pattern UNVERIFIED_MEDICINE_FACT = Pattern.compile(
            "(?i)\\b(?:[a-z][a-z-]{2,}|medicine|medication|drug)\\b\\s+"
                    + "(?:is used to |can )?(?:treats?|relieves?|prevents?)\\b");
    private static final Pattern OFFICIAL_DATA_CLAIM = Pattern.compile(
            "(?i)\\b(?:verified by (?:the )?(?:korean )?government|government[- ]verified"
                    + "|according to official data|official government data)\\b");
    private static final Pattern UNSAFE_MARKUP = Pattern.compile(
            "(?i)(?:https?://|javascript:|<\\s*/?\\s*[a-z][^>]*>|onerror\\s*=|\\[[^]]+]\\s*\\()");

    ValidationResult validate(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return ValidationResult.rejected(Set.of(ViolationCode.EMPTY_OUTPUT));
        }

        String normalized = Normalizer.normalize(candidate, Normalizer.Form.NFKC)
                .replaceAll("\\s+", " ")
                .trim();
        EnumSet<ViolationCode> violations = EnumSet.noneOf(ViolationCode.class);
        if (normalized.length() > MAX_LENGTH) {
            violations.add(ViolationCode.TOO_LONG);
        }
        addIfMatches(violations, ViolationCode.DEFINITE_DIAGNOSIS, DEFINITE_DIAGNOSIS, normalized);
        addIfMatches(violations, ViolationCode.PERSONAL_DOSE, PERSONAL_DOSE, normalized);
        addIfMatches(violations, ViolationCode.TREATMENT_SELECTION, TREATMENT_SELECTION, normalized);
        addIfMatches(
                violations,
                ViolationCode.PRESCRIPTION_DECISION,
                PRESCRIPTION_DECISION,
                normalized);
        addIfMatches(violations, ViolationCode.CURE_CLAIM, CURE_CLAIM, normalized);
        addIfMatches(violations, ViolationCode.SAFE_CLAIM, SAFE_CLAIM, normalized);
        addIfMatches(
                violations,
                ViolationCode.UNVERIFIED_MEDICINE_FACT,
                UNVERIFIED_MEDICINE_FACT,
                normalized);
        addIfMatches(
                violations,
                ViolationCode.OFFICIAL_DATA_CLAIM,
                OFFICIAL_DATA_CLAIM,
                normalized);
        addIfMatches(violations, ViolationCode.UNSAFE_MARKUP, UNSAFE_MARKUP, normalized);

        return violations.isEmpty()
                ? ValidationResult.accepted(new ValidatedGeneralExplanation(normalized))
                : ValidationResult.rejected(violations);
    }

    private static void addIfMatches(
            Set<ViolationCode> violations,
            ViolationCode code,
            Pattern pattern,
            String candidate) {
        if (pattern.matcher(candidate).find()) {
            violations.add(code);
        }
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

        private static ValidationResult accepted(ValidatedGeneralExplanation explanation) {
            return new ValidationResult(explanation, Set.of());
        }

        private static ValidationResult rejected(Set<ViolationCode> violations) {
            return new ValidationResult(null, violations);
        }
    }

    enum ViolationCode {
        EMPTY_OUTPUT,
        TOO_LONG,
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
