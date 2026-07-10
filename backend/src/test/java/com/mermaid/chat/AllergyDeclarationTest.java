package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The rule that decides whether a model may pick a medicine for this person.
 *
 * <p>Read the misses as carefully as the hits. This rule is over-eager by design, and the phrasings it
 * cannot see are recorded here rather than left to be discovered.
 */
class AllergyDeclarationTest {

    @Nested
    @DisplayName("declares an allergy")
    class Fires {

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "I have a headache but I am allergic to ibuprofen",
                    "I'm allergic to ibuprofen. What can I take?",
                    "i am ALLERGIC to aspirin",
                    "My son has a penicillin allergy",
                    "Do you have anything for a cold? I have allergies.",
                    "I went into anaphylaxis last time",
                    "an anaphylactic reaction to peanuts",
                    "I am lactose intolerant",
                    "sulfa intolerance",
                })
        void yes(String userText) {
            assertThat(AllergyDeclaration.presentIn(userText)).isTrue();
        }

        /**
         * Hay fever fires it too, and that person is told to see a pharmacist instead of being shown
         * an antihistamine. That is the trade we chose: a false positive costs one worse answer, a
         * false negative offers an allergic person a drug from the class they react to.
         */
        @Test
        @DisplayName("over-eager on purpose — seasonal allergies suppress the suggestion too")
        void seasonalAllergiesAlsoFire() {
            assertThat(AllergyDeclaration.presentIn("my allergies are acting up, runny nose")).isTrue();
        }
    }

    @Nested
    @DisplayName("does not declare an allergy")
    class Quiet {

        @ParameterizedTest
        @ValueSource(
                strings = {
                    "I have a headache",
                    "where is the nearest pharmacy?",
                    "My throat hurts and it is 11pm",
                    "",
                    "   ",
                })
        void no(String userText) {
            assertThat(AllergyDeclaration.presentIn(userText)).isFalse();
        }

        @Test
        void nullIsNotADeclaration() {
            assertThat(AllergyDeclaration.presentIn(null)).isFalse();
        }

        /**
         * A known miss, written down on purpose. The person is describing an allergy without naming
         * one, and no reviewed drug-class table would have helped either: we never learn that they
         * react to ibuprofen, so there is nothing to look up. The gap is in what we are told, not in
         * what we know.
         */
        @Test
        @DisplayName("a symptom description is not a declaration — we simply never learn of it")
        void symptomDescriptionIsMissed() {
            assertThat(AllergyDeclaration.presentIn("ibuprofen gives me hives")).isFalse();
        }

        /** English only, like {@link EmergencyTriage}. Recorded so nobody assumes otherwise. */
        @Test
        @DisplayName("Korean phrasings do not fire yet (TODO DEV-405)")
        void koreanIsNotCoveredYet() {
            assertThat(AllergyDeclaration.presentIn("이부프로펜 알레르기가 있어요")).isFalse();
        }

        /** {@code \b} does its job: no substring hit inside an unrelated word. */
        @Test
        void doesNotMatchInsideAnotherWord() {
            assertThat(AllergyDeclaration.presentIn("the allergenicity study")).isFalse();
        }
    }
}
