package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class GeneralExplanationPolicyTest {

    private final GeneralExplanationPolicy policy = new GeneralExplanationPolicy();

    @Test
    void allowsBoundedPossibilityLanguage() {
        GeneralExplanationPolicy.ValidationResult result = policy.validate(
                "A mild fever can happen with a common viral infection, but it has many possible causes.");

        assertThat(result.violations()).isEmpty();
        assertThat(result.explanation()).hasValueSatisfying(explanation -> assertThat(explanation.summary())
                .contains("many possible causes"));
    }

    @Test
    void allowsPossibilityWithoutTurningItIntoAUserDiagnosis() {
        GeneralExplanationPolicy.ValidationResult result = policy.validate(
                "It could be a cold, but symptoms alone cannot confirm that.");

        assertThat(result.violations()).isEmpty();
        assertThat(result.explanation()).isPresent();
    }

    @Test
    void allowsAPlainLanguageTermDefinition() {
        GeneralExplanationPolicy.ValidationResult result = policy.validate(
                "Inflammation is the body's general response to irritation or injury.");

        assertThat(result.violations()).isEmpty();
        assertThat(result.explanation()).isPresent();
    }

    @Test
    void blocksDefinitePersonalDiagnosis() {
        GeneralExplanationPolicy.ValidationResult result =
                policy.validate("You have influenza.");

        assertThat(result.explanation()).isEmpty();
        assertThat(result.violations())
                .containsExactly(GeneralExplanationPolicy.ViolationCode.DEFINITE_DIAGNOSIS);
    }

    @Test
    void blocksPersonalDose() {
        GeneralExplanationPolicy.ValidationResult result =
                policy.validate("Based on your weight, take two tablets every four hours.");

        assertThat(result.explanation()).isEmpty();
        assertThat(result.violations())
                .containsExactly(GeneralExplanationPolicy.ViolationCode.PERSONAL_DOSE);
    }

    @Test
    void blocksProbabilisticLanguageWhenItStillDiagnosesTheUser() {
        GeneralExplanationPolicy.ValidationResult result =
                policy.validate("You probably have influenza.");

        assertThat(result.explanation()).isEmpty();
        assertThat(result.violations())
                .containsExactly(GeneralExplanationPolicy.ViolationCode.DEFINITE_DIAGNOSIS);
    }

    @Test
    void blankAndOversizedOutputFailClosed() {
        assertThat(policy.validate("  ").violations())
                .containsExactly(GeneralExplanationPolicy.ViolationCode.EMPTY_OUTPUT);
        assertThat(policy.validate("a".repeat(1_201)).violations())
                .containsExactly(GeneralExplanationPolicy.ViolationCode.TOO_LONG);
    }

    @Test
    void normalizesWhitespaceBeforeIssuingTheOpaqueValue() {
        String summary = policy.validate("A mild fever can happen\nwith many possible causes.")
                .explanation()
                .orElseThrow()
                .summary();

        assertThat(summary).isEqualTo("A mild fever can happen with many possible causes.");
    }

    @ParameterizedTest
    @MethodSource("remainingBlockedCases")
    void blocksEveryDeclaredPolicyBoundary(
            String candidate, GeneralExplanationPolicy.ViolationCode expectedViolation) {
        GeneralExplanationPolicy.ValidationResult result = policy.validate(candidate);

        assertThat(result.explanation()).isEmpty();
        assertThat(result.violations()).containsExactly(expectedViolation);
    }

    private static Stream<Arguments> remainingBlockedCases() {
        return Stream.of(
                Arguments.of(
                        "Choose the treatment I should use for my cough.",
                        GeneralExplanationPolicy.ViolationCode.TREATMENT_SELECTION),
                Arguments.of(
                        "Decide whether I need a prescription medicine.",
                        GeneralExplanationPolicy.ViolationCode.PRESCRIPTION_DECISION),
                Arguments.of(
                        "This remedy will cure your infection.",
                        GeneralExplanationPolicy.ViolationCode.CURE_CLAIM),
                Arguments.of(
                        "This medicine is safe for you.",
                        GeneralExplanationPolicy.ViolationCode.SAFE_CLAIM),
                Arguments.of(
                        "Ibuprofen treats this condition.",
                        GeneralExplanationPolicy.ViolationCode.UNVERIFIED_MEDICINE_FACT),
                Arguments.of(
                        "This explanation is verified by the Korean government.",
                        GeneralExplanationPolicy.ViolationCode.OFFICIAL_DATA_CLAIM),
                Arguments.of(
                        "<script>alert('diagnosis')</script>",
                        GeneralExplanationPolicy.ViolationCode.UNSAFE_MARKUP));
    }
}
