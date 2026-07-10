package com.mermaid.drug;

import static org.assertj.core.api.Assertions.assertThat;

import com.mermaid.profile.domain.MatchConfidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The rule this class exists to enforce (spec §2-12): an allergy may block a medicine only on an
 * exact match or a synonym a human reviewed. Everything else warns.
 */
class IngredientNormalizerTest {

    private final IngredientNormalizer normalizer = new IngredientNormalizer();

    @Nested
    @DisplayName("canonicalisation")
    class Canonicalisation {

        @ParameterizedTest
        @CsvSource({
            "Ibuprofen, ibuprofen",
            "IBUPROFEN, ibuprofen",
            "  Ibuprofen  , ibuprofen",
            "Ibuprofen 200mg, ibuprofen",
            "Ibuprofen 200 mg, ibuprofen",
            "Ibuprofen (Advil), ibuprofen",
            "Acetaminophen 160밀리그램, acetaminophen",
            "Acetylsalicylic-Acid, acetylsalicylic acid",
            "Acetaminophen/Caffeine, acetaminophen caffeine",
        })
        void stripsDoseBrandAndCase(String raw, String expected) {
            assertThat(normalizer.canonicalize(raw)).isEqualTo(expected);
        }

        @Test
        @DisplayName("full-width characters normalise to ASCII")
        void unicodeNfkc() {
            assertThat(normalizer.canonicalize("Ｉｂｕｐｒｏｆｅｎ")).isEqualTo("ibuprofen");
        }

        @ParameterizedTest
        @ValueSource(strings = {"", "   ", "(500mg)"})
        @DisplayName("input with no ingredient left yields UNKNOWN, not an empty key")
        void emptyAfterStripping(String raw) {
            assertThat(normalizer.normalize(raw).confidence()).isEqualTo(MatchConfidence.UNKNOWN);
            assertThat(normalizer.normalize(raw).key()).isNull();
        }

        @Test
        void nullIsUnknown() {
            assertThat(normalizer.normalize(null).confidence()).isEqualTo(MatchConfidence.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("physical-form qualifiers do not change the allergen")
    class FormQualifiers {

        @ParameterizedTest
        @CsvSource({
            "Acetaminophen Granules, acetaminophen",
            "Acetaminophen Micronized, acetaminophen",
            "Anhydrous Caffeine, caffeine",
            "Ibuprofen Powder, ibuprofen",
        })
        @DisplayName("the qualifier is stripped, so the allergen still matches")
        void stripped(String raw, String expected) {
            assertThat(normalizer.canonicalize(raw)).isEqualTo(expected);
        }

        @Test
        @DisplayName("'Acetaminophen Micronized' BLOCKS an acetaminophen allergy — it is the same drug")
        void micronizedBlocks() {
            // Found in a live 허가정보 response for 어린이타이레놀현탁액. Before the qualifier strip this
            // came back as a mere `warning`, which is a false negative in the direction that hurts.
            MatchConfidence c = normalizer.compare("Acetaminophen Micronized", "acetaminophen");

            assertThat(c.canBlock()).isTrue();
        }

        @Test
        @DisplayName("a salt form is NOT stripped — that would over-block")
        void saltsSurvive() {
            assertThat(normalizer.canonicalize("Chlorpheniramine Maleate")).isEqualTo("chlorpheniramine maleate");
            assertThat(normalizer.canonicalize("Benztropine Mesylate")).isEqualTo("benztropine mesylate");
        }

        @Test
        @DisplayName("a genuinely two-word ingredient is left alone")
        void compoundNameSurvives() {
            assertThat(normalizer.canonicalize("Sodium Chloride")).isEqualTo("sodium chloride");
        }
    }

    @Nested
    @DisplayName("toSearchTerm() speaks the upstream's dialect")
    class SearchTerm {

        @Test
        @DisplayName("lower case becomes Title Case — the upstream substring match is case-sensitive")
        void titleCases() {
            // `ibuprofen` finds 142 products upstream, every one of them Dexibuprofen.
            // `Ibuprofen` finds 282, which is what an allergy question actually needs.
            assertThat(normalizer.toSearchTerm("ibuprofen")).isEqualTo("Ibuprofen");
            assertThat(normalizer.toSearchTerm("IBUPROFEN 200mg")).isEqualTo("Ibuprofen");
            assertThat(normalizer.toSearchTerm("sodium chloride")).isEqualTo("Sodium Chloride");
        }

        @Test
        @DisplayName("a synonym is resolved before capitalisation")
        void resolvesSynonym() {
            assertThat(normalizer.toSearchTerm("paracetamol")).isEqualTo("Acetaminophen");
            assertThat(normalizer.toSearchTerm("Aspirin")).isEqualTo("Acetylsalicylic Acid");
        }

        @Test
        @DisplayName("nothing usable yields an empty term, and the caller must not query")
        void emptyTerm() {
            assertThat(normalizer.toSearchTerm("(500mg)")).isEmpty();
        }
    }

    @Nested
    @DisplayName("the reviewed dictionary")
    class Synonyms {

        @Test
        @DisplayName("paracetamol resolves to acetaminophen and is marked SYNONYM")
        void reviewedSynonym() {
            var term = normalizer.normalize("Paracetamol");

            assertThat(term.key()).isEqualTo("acetaminophen");
            assertThat(term.confidence()).isEqualTo(MatchConfidence.SYNONYM);
            assertThat(term.confidence().canBlock()).isTrue();
        }

        @Test
        @DisplayName("aspirin resolves to acetylsalicylic acid")
        void brandTurnedGeneric() {
            assertThat(normalizer.normalize("Aspirin").key()).isEqualTo("acetylsalicylic acid");
        }

        @Test
        @DisplayName("a name already canonical is EXACT")
        void alreadyCanonical() {
            assertThat(normalizer.normalize("ibuprofen").confidence()).isEqualTo(MatchConfidence.EXACT);
        }
    }

    @Nested
    @DisplayName("comparing a drug's ingredient against an avoided one")
    class Compare {

        @Test
        @DisplayName("identical ingredient blocks")
        void exactMatchBlocks() {
            MatchConfidence c = normalizer.compare("Ibuprofen", "ibuprofen");
            assertThat(c).isEqualTo(MatchConfidence.EXACT);
            assertThat(c.canBlock()).isTrue();
        }

        @Test
        @DisplayName("a reviewed synonym blocks — Paracetamol vs acetaminophen")
        void synonymBlocks() {
            MatchConfidence c = normalizer.compare("Paracetamol 500mg", "acetaminophen");
            assertThat(c).isEqualTo(MatchConfidence.SYNONYM);
            assertThat(c.canBlock()).isTrue();
        }

        @Test
        @DisplayName("the real MAIN_INGR_ENG value for 어린이타이레놀 blocks an acetaminophen allergy")
        void realWorldGranules() {
            // "Acetaminophen Granules" is exactly what 허가정보 returned for ITEM_SEQ 202005623.
            MatchConfidence c = normalizer.compare("Acetaminophen Granules", "acetaminophen");
            assertThat(c.canBlock()).isTrue();
        }

        @Test
        @DisplayName("a compound product WARNS, it does not block")
        void compoundWarns() {
            // 허가정보 really returns ingredient lists like this one.
            MatchConfidence c =
                    normalizer.compare(
                            "Acetaminophen/Anhydrous Caffeine/Chlorpheniramine Maleate", "ibuprofen");
            assertThat(c).isEqualTo(MatchConfidence.UNKNOWN); // ibuprofen is not in there at all
        }

        @Test
        @DisplayName("an ingredient present inside a compound yields PARTIAL, never a block")
        void memberOfCompoundIsPartial() {
            MatchConfidence c =
                    normalizer.compare("Acetaminophen/Anhydrous Caffeine", "acetaminophen");

            assertThat(c).isEqualTo(MatchConfidence.PARTIAL);
            assertThat(c.canBlock()).as("a compound is not an identity").isFalse();
        }

        @Test
        @DisplayName("spelling similarity does NOT block — the whole point of §2-12")
        void similarSpellingDoesNotBlock() {
            assertThat(normalizer.compare("Ibuprofen", "ibuprofene").canBlock()).isFalse();
            assertThat(normalizer.compare("Acetaminophen", "acetamiophen").canBlock()).isFalse();
            assertThat(normalizer.compare("Aspirin", "aspirinoid").canBlock()).isFalse();
        }

        @Test
        @DisplayName("word boundaries: aspirinoid does not contain aspirin as an ingredient")
        void wordBoundaries() {
            assertThat(normalizer.compare("Aspirinoid", "acetylsalicylic acid"))
                    .isEqualTo(MatchConfidence.UNKNOWN);
        }

        @Test
        @DisplayName("unrelated ingredients do not match")
        void unrelated() {
            assertThat(normalizer.compare("Ibuprofen", "acetaminophen")).isEqualTo(MatchConfidence.UNKNOWN);
        }

        @Test
        @DisplayName("null or blank input cannot be compared")
        void nullSafe() {
            assertThat(normalizer.compare(null, "ibuprofen")).isEqualTo(MatchConfidence.UNKNOWN);
            assertThat(normalizer.compare("Ibuprofen", null)).isEqualTo(MatchConfidence.UNKNOWN);
            assertThat(normalizer.compare("Ibuprofen", "  ")).isEqualTo(MatchConfidence.UNKNOWN);
        }
    }

    @Nested
    @DisplayName("only EXACT and SYNONYM may block")
    class BlockingRule {

        @Test
        void confidenceGate() {
            assertThat(MatchConfidence.EXACT.canBlock()).isTrue();
            assertThat(MatchConfidence.SYNONYM.canBlock()).isTrue();
            assertThat(MatchConfidence.PARTIAL.canBlock()).isFalse();
            assertThat(MatchConfidence.UNKNOWN.canBlock()).isFalse();
        }
    }
}
