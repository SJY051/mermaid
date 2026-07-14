package com.mermaid.drug;

import static org.assertj.core.api.Assertions.assertThat;

import com.mermaid.drug.IngredientController.AllergenOption;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The picker's one promise: everything it offers actually binds (spec 005 FR-015).
 *
 * <p>An option that failed {@code isReviewedBinding} would send the person straight back into the
 * clarification the picker exists to end — so the invariant is checked option by option, against
 * the real dictionary, not a fixture.
 */
class IngredientControllerTest {

    private final IngredientNormalizer normalizer = new IngredientNormalizer();
    private final IngredientController controller = new IngredientController(normalizer);

    @Test
    @DisplayName("FR-015: every offered option resolves with blocking authority")
    void everyOfferedOptionBinds() {
        List<AllergenOption> options = controller.allergenOptions();

        assertThat(options).isNotEmpty();
        for (AllergenOption option : options) {
            // The key is what the client sends back in exclude_ingredients; it must resolve the
            // way DrugContextRetriever resolves it, or selecting it clarifies instead of binding.
            IngredientNormalizer.NormalizedTerm normalized = normalizer.normalize(option.key());
            assertThat(normalizer.isReviewedBinding(normalized))
                    .as("picker option '%s' must bind — an option that cannot bind reopens the"
                            + " clarify loop", option.key())
                    .isTrue();
        }
    }

    @Test
    @DisplayName("options come from the dictionary and carry display labels")
    void optionsMirrorTheDictionary() {
        List<AllergenOption> options = controller.allergenOptions();

        assertThat(options).extracting(AllergenOption::key)
                .containsExactlyElementsOf(normalizer.canonicalKeys())
                .contains("ibuprofen", "acetaminophen");
        // Labels are the search-term casing ("Ibuprofen"), which is also what the ministry data
        // uses — display only, the key is what travels back.
        assertThat(options).allSatisfy(option ->
                assertThat(option.label()).isEqualTo(normalizer.toSearchTerm(option.key())));
    }
}
