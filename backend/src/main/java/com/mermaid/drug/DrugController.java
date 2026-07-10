package com.mermaid.drug;

import com.mermaid.common.ApiException;
import com.mermaid.common.ErrorCode;
import com.mermaid.drug.domain.Drug;
import jakarta.validation.constraints.Size;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

/**
 * Medicine lookup (FR-03, FR-04, FR-07).
 *
 * <p>Read-only. The original API table listed {@code DELETE /api/v1/drugs/{id}} returning "single
 * item detail JSON" — a copy-paste of the GET row. Drugs are reference data we do not own; the Delete
 * half of CRUD is demonstrated on favorites and allergies instead (spec §2-7).
 */
@RestController
@RequestMapping("/api/v1/drugs")
@RequiredArgsConstructor
@Validated
public class DrugController {

    private final DrugService drugService;
    private final IngredientNormalizer normalizer;

    /**
     * e.g. {@code ?query=타이레놀&exclude_ingredients=Ibuprofen,Aspirin}
     *
     * <p>Exactly one of {@code query} or {@code ingredient} is required. {@code ingredient} must be
     * English and capitalised — the upstream service is case-sensitive.
     */
    @GetMapping
    public List<Drug> search(
            @RequestParam(required = false) @Size(min = 2, max = 100) String query,
            @RequestParam(required = false) @Size(min = 2, max = 100) String ingredient,
            @RequestParam(name = "exclude_ingredients", required = false) Set<String> excludeIngredients) {

        if ((query == null) == (ingredient == null)) {
            throw new ApiException(
                    ErrorCode.INVALID_REQUEST, "exactly one of `query` or `ingredient` is required");
        }

        Set<String> avoided = normalizeAvoided(excludeIngredients);
        return query != null
                ? drugService.searchByName(query, avoided)
                : drugService.searchByIngredient(ingredient, avoided);
    }

    /** {@code itemSeq} is the MFDS product code, e.g. {@code 202005623}. */
    @GetMapping("/{itemSeq}")
    public Drug detail(
            @PathVariable String itemSeq,
            @RequestParam(name = "exclude_ingredients", required = false) Set<String> excludeIngredients) {
        return drugService.detail(itemSeq, normalizeAvoided(excludeIngredients));
    }

    /**
     * The caller sends raw text ("Paracetamol 500mg"); the comparison needs a normalised key.
     * Normalising here means every entry point uses the same form as the stored profile does.
     */
    private Set<String> normalizeAvoided(Set<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        for (String r : raw) {
            String key = normalizer.normalize(r).key();
            if (key != null) {
                keys.add(key);
            }
        }
        return keys;
    }
}
