package com.mermaid.drug;

import static org.assertj.core.api.Assertions.assertThat;

import com.mermaid.drug.AllergenBinder.BoundAllergens;
import java.util.List;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * SCAFFOLD (DEV-560, spec 005). Test skeletons for free-text allergy binding.
 *
 * <p>Each test is {@link Disabled} until {@link AllergenBinder} is implemented. They are written to
 * be <b>red the moment they are enabled against the stub</b> (which resolves nothing) and green only
 * once the real binding lands — the red-before/green-after discipline (SC-005). The implementer
 * removes {@code @Disabled} one at a time, watches it fail, then makes it pass.
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
    @Disabled("DEV-560: enable when AllergenBinder implements FR-001 origin binding")
    @DisplayName("SC-003: a candidate the user never typed does not resolve (origin binding)")
    void modelProposedAllergenWithoutUserOriginIsDropped() {
        BoundAllergens bound = binder.bind(List.of("ibuprofen"), "I have a headache");

        // The user did not name ibuprofen; a model-proposed one must not inject an allergy.
        assertThat(bound.avoidedKeys()).isEmpty();
        assertThat(bound.anyResolved()).isFalse();
    }

    @Test
    @Disabled("DEV-560: enable when AllergenBinder implements FR-001/FR-002 binding")
    @DisplayName("SC-003: an allergen the user typed resolves to an avoided key")
    void userTypedAllergenResolves() {
        BoundAllergens bound = binder.bind(List.of("ibuprofen"), "I'm allergic to ibuprofen");

        assertThat(bound.anyResolved()).isTrue();
        assertThat(bound.avoidedKeys()).isNotEmpty();
    }

    @Test
    @Disabled("DEV-560: enable when AllergenBinder implements FR-002 signed-row boundary")
    @DisplayName("SC-002: only a signed synonym/exact key may enter the avoided set")
    void unsignedGuessDoesNotEnterAvoidedSet() {
        // A name with no EXACT or reviewed-SYNONYM key must NOT produce a blocking avoided key; it is
        // reported as unresolved (warning at most), per AGENTS.md 2-6 / 2-12.
        BoundAllergens bound = binder.bind(List.of("notarealingredientxyz"), "allergic to notarealingredientxyz");

        assertThat(bound.avoidedKeys()).isEmpty();
        assertThat(bound.unresolved()).contains("notarealingredientxyz");
    }

    @Test
    @Disabled("DEV-560: SC-001 lives in DrugContextRetriever — declared allergy + empty avoided → clarifying question, never no_match_found")
    @DisplayName("SC-001 placeholder: unresolved declared allergy fails closed to a clarifying question")
    void declaredButUnresolvedAllergyAsksInsteadOfNoMatchFound() {
        // TODO(DEV-560): move to a DrugContextRetriever test once the fail-closed path is wired.
        //  Given AllergyDeclaration fires but nothing binds, the answer must carry
        //  AllergyClarification.QUESTION in clarifyingQuestions[], name no medicine, and keep
        //  drugs empty — never a silent no_match_found (AGENTS.md 2-2).
    }
}
