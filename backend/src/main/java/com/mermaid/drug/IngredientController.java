package com.mermaid.drug;

import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The bounded allergen vocabulary for the client's picker (spec 005 FR-015, DEV-561).
 *
 * <p>Why the server publishes this instead of the client hard-coding it: the picker's promise is
 * "everything you can select here actually binds" — a selection must always resolve, or the picker
 * reintroduces the clarify loop it exists to end. That promise only holds while the option list and
 * the ingredient dictionary are the same thing, so the list is derived from the dictionary at the
 * one place the dictionary lives. The FR-007 profile stores the same keys, which is what lets a
 * stored profile skip the clarification entirely.
 */
@RestController
@RequestMapping("/api/v1/ingredients")
@RequiredArgsConstructor
public class IngredientController {

    private final IngredientNormalizer normalizer;

    @GetMapping("/allergen-options")
    public List<AllergenOption> allergenOptions() {
        return normalizer.canonicalKeys().stream()
                .map(key -> new AllergenOption(key, normalizer.toSearchTerm(key)))
                .toList();
    }

    /**
     * @param key the canonical key the client sends back in {@code mermaid.exclude_ingredients}
     * @param label the human-readable form ("Ibuprofen"); display only, never sent back
     */
    public record AllergenOption(String key, String label) {}
}
