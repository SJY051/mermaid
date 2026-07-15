package com.mermaid.chat;

import com.mermaid.chat.DrugContextRetriever.GroundedDrug;
import com.mermaid.chat.dto.AllergyCheck;
import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.chat.dto.UiAction;
import com.mermaid.common.SourceRef;
import com.mermaid.drug.IngredientNormalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * The checks a JSON Schema cannot express (spec §2-15).
 *
 * <p>A model can emit perfectly-shaped JSON that names a medicine we never looked up. The schema
 * says nothing is wrong; the content is fabricated. These cross-field invariants are the only thing
 * standing between an invented Korean drug name and a sick person reading it as fact.
 *
 * <p>Invariant #6 needs the server-owned product, source, and ingredient identities retrieved during
 * pass 1 of the RAG flow. The rest are self-contained.
 */
@Component
public class AnswerValidator {

    /** Anything that could smuggle a link or HTML-looking markup into the UI (invariant #7). */
    private static final Pattern FORBIDDEN_MARKUP =
            Pattern.compile("(?i)(https?://|javascript:|<\\s*/?\\s*[a-z][^>]*>|onerror\\s*=)");

    private final IngredientNormalizer ingredientNormalizer;

    public AnswerValidator(IngredientNormalizer ingredientNormalizer) {
        this.ingredientNormalizer = ingredientNormalizer;
    }

    /** Stable, value-free identifiers safe to aggregate in application logs. */
    public enum ViolationCode {
        INV1_UNKNOWN_DRUG_SOURCE,
        INV2_BLOCKED_WITHOUT_MATCH,
        INV3_LIVE_WITH_FIXTURE_SOURCE,
        INV4_EMERGENCY_ACTION_MISSING,
        INV4_EMERGENCY_DRUGS_PRESENT,
        INV5_GUIDANCE_SOURCE_MISSING,
        INV5_GUIDANCE_SOURCE_UNKNOWN,
        INV6_PRODUCT_NOT_RETRIEVED,
        INV6_PRODUCT_SOURCE_MISMATCH,
        INV6_INGREDIENT_MISMATCH,
        INV7_FORBIDDEN_MARKUP
    }

    /**
     * @param groundedDrugs products fetched from the MFDS APIs this turn, keyed by Korean product
     *     name. Empty means we retrieved nothing, so the answer must not name any drug.
     * @return every violation found. Empty list means the answer may be shown.
     */
    public List<ViolationCode> validate(
            MermAidAnswer answer, Map<String, GroundedDrug> groundedDrugs) {
        List<ViolationCode> violations = new ArrayList<>();

        Set<String> sourceIds =
                answer.sourceRefs() == null
                        ? Set.of()
                        : answer.sourceRefs().stream()
                                .filter(Objects::nonNull)
                                .map(SourceRef::id)
                                .collect(Collectors.toSet());

        // 1. Every drug cites a source we actually have.
        for (MermAidAnswer.DrugCard drug : nullSafe(answer.drugs())) {
            if (!sourceIds.contains(drug.sourceRefId())) {
                violations.add(ViolationCode.INV1_UNKNOWN_DRUG_SOURCE);
            }
        }

        // 2. A BLOCKED verdict must name what it matched, or it is not actionable.
        for (MermAidAnswer.DrugCard drug : nullSafe(answer.drugs())) {
            AllergyCheck check = drug.allergyCheck();
            if (check != null
                    && check.status() == AllergyCheck.Status.BLOCKED
                    && nullSafe(check.matchedIngredients()).isEmpty()) {
                violations.add(ViolationCode.INV2_BLOCKED_WITHOUT_MATCH);
            }
        }

        // 3. A "live" answer cannot rest on fixture data.
        if (answer.dataStatus() == MermAidAnswer.DataStatus.LIVE) {
            boolean anyFixture =
                    nullSafe(answer.sourceRefs()).stream()
                            .filter(Objects::nonNull)
                            .anyMatch(s -> s.dataMode() == SourceRef.DataMode.FIXTURE);
            if (anyFixture) {
                violations.add(ViolationCode.INV3_LIVE_WITH_FIXTURE_SOURCE);
            }
        }

        // 4. An emergency must always offer the call and never recommend a medicine. Pre-model
        //    triage already emits no drugs; this independently closes any later model-authored path.
        if (answer.urgency() != null
                && answer.urgency().level() == MermAidAnswer.Urgency.Level.EMERGENCY) {
            if (!hasEmergencyCall(answer)) {
                violations.add(ViolationCode.INV4_EMERGENCY_ACTION_MISSING);
            }
            if (!nullSafe(answer.drugs()).isEmpty()) {
                violations.add(ViolationCode.INV4_EMERGENCY_DRUGS_PRESENT);
            }
        }

        // 5. Official claims must cite a source, and every guidance citation must be one we hold.
        for (MermAidAnswer.Guidance guidance : nullSafe(answer.guidance())) {
            List<String> citations = nullSafe(guidance.sourceRefIds());
            if (guidance.evidence() == MermAidAnswer.Guidance.Evidence.OFFICIAL_DATA
                    && citations.isEmpty()) {
                violations.add(ViolationCode.INV5_GUIDANCE_SOURCE_MISSING);
            }
            for (String sourceRefId : citations) {
                if (sourceRefId == null || !sourceIds.contains(sourceRefId)) {
                    violations.add(ViolationCode.INV5_GUIDANCE_SOURCE_UNKNOWN);
                }
            }
        }

        // 6. A card must match one retrieved product, including its own source and ingredient set.
        for (MermAidAnswer.DrugCard drug : nullSafe(answer.drugs())) {
            GroundedDrug grounded = groundedDrugs.get(drug.productNameKo());
            if (grounded == null) {
                violations.add(ViolationCode.INV6_PRODUCT_NOT_RETRIEVED);
                continue;
            }
            if (sourceIds.contains(drug.sourceRefId())
                    && !Objects.equals(grounded.sourceRefId(), drug.sourceRefId())) {
                violations.add(ViolationCode.INV6_PRODUCT_SOURCE_MISMATCH);
            }
            Set<String> answerIngredientKeys = ingredientKeys(drug.ingredients());
            if (answerIngredientKeys == null
                    || !grounded.ingredientKeys().equals(answerIngredientKeys)) {
                violations.add(ViolationCode.INV6_INGREDIENT_MISMATCH);
            }
        }

        // 7. No links or HTML-looking markup from a model may reach a rendered field.
        for (String text : userVisibleText(answer)) {
            if (text != null && FORBIDDEN_MARKUP.matcher(text).find()) {
                violations.add(ViolationCode.INV7_FORBIDDEN_MARKUP);
            }
        }

        return violations;
    }

    private Set<String> ingredientKeys(List<MermAidAnswer.Ingredient> ingredients) {
        if (ingredients == null) {
            return null;
        }
        Set<String> keys = new HashSet<>();
        for (MermAidAnswer.Ingredient ingredient : ingredients) {
            if (ingredient == null || ingredient.nameEn() == null) {
                return null;
            }
            String key = ingredientNormalizer.normalizeIdentity(ingredient.nameEn()).key();
            if (key == null) {
                return null;
            }
            keys.add(key);
        }
        return Set.copyOf(keys);
    }

    private boolean hasEmergencyCall(MermAidAnswer answer) {
        return java.util.stream.Stream.concat(
                        nullSafe(answer.uiActions()).stream(),
                        nullSafe(answer.urgency().actions()).stream())
                .anyMatch(a -> a instanceof UiAction.ShowEmergencyCall);
    }

    private List<String> userVisibleText(MermAidAnswer answer) {
        List<String> texts = new ArrayList<>();
        if (answer.urgency() != null) {
            texts.add(answer.urgency().title());
            texts.add(answer.urgency().message());
        }
        texts.add(answer.summary());
        texts.add(answer.disclaimer());
        texts.addAll(nullSafe(answer.warnings()));
        texts.addAll(nullSafe(answer.clarifyingQuestions()));
        for (MermAidAnswer.Guidance guidance : nullSafe(answer.guidance())) {
            texts.add(guidance.body());
        }
        for (MermAidAnswer.DrugCard drug : nullSafe(answer.drugs())) {
            texts.add(drug.productNameKo());
            texts.add(drug.productNameEn());
            texts.add(drug.indicationSummary());
            texts.add(drug.directionsSummary());
            // A rendered field the model still writes, so it is scanned like the two above it. The
            // card's `warnings` are server-authored now (invariant 8) and could be dropped from this
            // list — they stay, because a rendered string with no scan is one refactor away from
            // being model text again, and the scan costs nothing.
            texts.add(drug.labelCautions());
            texts.addAll(nullSafe(drug.warnings()));
            if (drug.allergyCheck() != null) {
                texts.addAll(nullSafe(drug.allergyCheck().matchedIngredients()));
            }
        }
        return texts;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }
}
