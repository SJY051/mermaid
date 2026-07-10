package com.mermaid.drug;

import com.mermaid.drug.dto.DrugSummary;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * Medicine lookup (FR-03, FR-04).
 *
 * <p>Read-only. The original API table listed {@code DELETE /api/v1/drugs/{id}} returning "single
 * item detail JSON" — a copy-paste of the GET row. Drugs are reference data we do not own; the
 * Delete half of CRUD is demonstrated on favorites and allergies instead (spec §2-7).
 */
@RestController
@RequestMapping("/api/v1/drugs")
@RequiredArgsConstructor
public class DrugController {

    private final DrugService drugService;

    /** e.g. {@code ?symptom=sore%20throat&exclude_ingredients=ibuprofen,aspirin} */
    @GetMapping
    public List<DrugSummary> search(
            @RequestParam String symptom,
            @RequestParam(name = "exclude_ingredients", required = false) Set<String> excludeIngredients) {
        return drugService.search(symptom, excludeIngredients == null ? Set.of() : excludeIngredients);
    }

    @GetMapping("/{itemSeq}")
    public DrugSummary detail(@PathVariable String itemSeq) {
        return drugService.detail(itemSeq);
    }
}
