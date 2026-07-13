package com.mermaid.drug;

import static org.assertj.core.api.Assertions.assertThat;

import com.mermaid.drug.AllergenBinder.BoundAllergens;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * DEV-560 (spec 005). Contracts for free-text allergy binding.
 *
 * <p>Each test was enabled against the resolve-nothing scaffold before implementation and observed
 * red. They turn green only once the real binding lands (SC-005).
 *
 * <p>Coverage map for the spec's success criteria:
 * <ul>
 *   <li><b>SC-003 origin binding</b> and <b>SC-002 signed rows</b> live here (unit, on the binder).
 *   <li><b>SC-001 fail-closed clarifying question</b> lives in a future {@code DrugContextRetriever}
 *       test: declared allergy + empty avoided set must return {@code AllergyClarification.QUESTION},
 *       never {@code no_match_found}. Placeholder below.
 *   <li><b>SC-004 opt-in + masking</b> lives in future profile/storage tests
 *       ({@code AllergyBadge.test.tsx} / {@code storage.test.ts} and the server profile layer).
 * </ul>
 */
class AllergenBinderScaffoldTest {

    private final AllergenBinder binder = new AllergenBinder(new IngredientNormalizer());

    @Test
    @DisplayName("SC-003: a candidate the user never typed does not resolve (origin binding)")
    void modelProposedAllergenWithoutUserOriginIsDropped() {
        BoundAllergens bound =
                binder.bind(List.of("ibuprofen", "aspirin"), "I'm allergic to ibuprofen");

        // The user named ibuprofen, not aspirin. The model-proposed aspirin must have zero effect.
        assertThat(bound.avoidedKeys()).containsExactly("ibuprofen");
        assertThat(bound.anyResolved()).isTrue();
        assertThat(bound.unresolved()).isEmpty();
    }

    @Test
    @DisplayName("SC-003: an allergen the user typed resolves to an avoided key")
    void userTypedAllergenResolves() {
        BoundAllergens bound = binder.bind(List.of("ibuprofen"), "I'm allergic to ibuprofen");

        assertThat(bound.anyResolved()).isTrue();
        assertThat(bound.avoidedKeys()).containsExactly("ibuprofen");
    }

    @Test
    @DisplayName("SC-002: only a signed synonym/exact key may enter the avoided set")
    void unsignedGuessDoesNotEnterAvoidedSet() {
        // This row exists but has no human reviewer. It may aid lookup, but must never gain block
        // authority on the free-text path (AGENTS.md 2-6 / spec FR-002).
        BoundAllergens bound = binder.bind(List.of("paracetamol"), "allergic to paracetamol");

        assertThat(bound.avoidedKeys()).isEmpty();
        assertThat(bound.anyResolved()).isFalse();
        assertThat(bound.unresolved()).containsExactly("paracetamol");
    }

    @Test
    @DisplayName("FR-006: case-folding resolves an exact canonical name, which may block")
    void caseFoldedExactCanonicalResolves() {
        // An exact canonical name, in any case, is identity — §2-12 lets it block.
        BoundAllergens caseVariant = binder.bind(List.of("IBUPROFEN"), "I'm allergic to IBUPROFEN");

        assertThat(caseVariant.avoidedKeys()).containsExactly("ibuprofen");
    }

    @Test
    @DisplayName("FR-006 / §2-6: an unsigned spelling variant feeds lookup but never blocks")
    void unsignedSpellingVariantDoesNotBind() {
        // "Ibuprofin" has no human-signed synonyms.tsv row. An in-code spelling alias helps
        // retrieval find the record, but must NOT acquire blocking authority (AGENTS.md 2-6): that
        // would let a typo block a drug that not even the real, unsigned salt-form rows can block.
        // It fails closed to the clarifying question instead of a silent block. A human signs the
        // spelling in the TSV to make it block. Regression guard for the Codex P0 on #59.
        BoundAllergens spelling = binder.bind(List.of("Ibuprofin"), "I'm allergic to Ibuprofin");

        assertThat(spelling.avoidedKeys()).isEmpty();
        assertThat(spelling.anyResolved()).isFalse();
        assertThat(spelling.unresolved()).containsExactly("Ibuprofin");
    }
}
