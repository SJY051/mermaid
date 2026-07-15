package com.mermaid.chat;

import com.mermaid.chat.AnswerValidator.ViolationCode;
import com.mermaid.chat.DrugContextRetriever.DrugContext;
import com.mermaid.chat.DrugContextRetriever.GroundedDrug;
import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.common.SourceRef;
import com.mermaid.drug.IngredientNormalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/** Builds the server-owned product-card answer used while whole-answer Pass 2 is disabled. */
@Slf4j
@Component
public class ServerAuthoredAnswerBuilder {

    public static final String ANSWER_ID = "server-canonical-drugs";
    public static final String CARD_ID_PREFIX = "server-card:";
    public static final String SUMMARY =
            "Official medicine records were found and verified. The cards below show information "
                    + "taken directly from those records. English indication and caution explanations "
                    + "are temporarily unavailable. Check with a licensed pharmacist or doctor before "
                    + "taking any medicine.";
    public static final String URGENCY_TITLE = "Urgency was not assessed";
    public static final String URGENCY_MESSAGE =
            "Medicine records cannot determine how urgent your symptoms are. Contact a pharmacist "
                    + "or doctor if you need help deciding what to do.";
    public static final String INCONSISTENT_CONTEXT_SUMMARY =
            "I could not verify the retrieved medicine records, so I will not show them. "
                    + "Please try again or visit a pharmacy.";

    private final IngredientNormalizer ingredientNormalizer;
    private final AnswerValidator answerValidator;

    public ServerAuthoredAnswerBuilder(
            IngredientNormalizer ingredientNormalizer, AnswerValidator answerValidator) {
        this.ingredientNormalizer = ingredientNormalizer;
        this.answerValidator = answerValidator;
    }

    public Optional<MermAidAnswer> build(DrugContext context) {
        List<SourceRef> sources = context.sources();
        Map<String, GroundedDrug> groundedDrugs = context.groundedDrugs();
        if (sources.isEmpty() || groundedDrugs.isEmpty() || sources.size() != groundedDrugs.size()) {
            return reject(MappingFailure.CARDINALITY_MISMATCH);
        }

        Map<String, SourceRef> sourcesById = new LinkedHashMap<>();
        for (SourceRef source : sources) {
            if (source.id() == null || source.id().isBlank()) {
                return reject(MappingFailure.SOURCE_ID_MISSING);
            }
            if (source.dataMode() == null) {
                return reject(MappingFailure.SOURCE_MODE_MISSING);
            }
            if (sourcesById.putIfAbsent(source.id(), source) != null) {
                return reject(MappingFailure.DUPLICATE_SOURCE_ID);
            }
        }

        Map<String, ProductRecord> productsBySourceId = new LinkedHashMap<>();
        for (Map.Entry<String, GroundedDrug> entry : groundedDrugs.entrySet()) {
            String productNameKo = entry.getKey();
            GroundedDrug grounded = entry.getValue();
            if (productNameKo == null || productNameKo.isBlank()) {
                return reject(MappingFailure.PRODUCT_NAME_MISSING);
            }
            if (grounded.sourceRefId() == null || grounded.sourceRefId().isBlank()) {
                return reject(MappingFailure.GROUNDED_SOURCE_ID_MISSING);
            }
            if (!sourcesById.containsKey(grounded.sourceRefId())) {
                return reject(MappingFailure.GROUNDED_SOURCE_UNKNOWN);
            }
            if (productsBySourceId.putIfAbsent(
                            grounded.sourceRefId(), new ProductRecord(productNameKo, grounded))
                    != null) {
                return reject(MappingFailure.DUPLICATE_GROUNDED_SOURCE_ID);
            }
        }
        if (!sourcesById.keySet().equals(productsBySourceId.keySet())) {
            return reject(MappingFailure.SOURCE_PRODUCT_MISMATCH);
        }

        List<MermAidAnswer.DrugCard> cards = new ArrayList<>(sources.size());
        for (SourceRef source : sources) {
            ProductRecord product = productsBySourceId.get(source.id());
            Optional<List<MermAidAnswer.Ingredient>> ingredients = ingredientsOf(product.grounded());
            if (ingredients.isEmpty()) {
                return reject(MappingFailure.INGREDIENT_KEYS_MISMATCH);
            }
            if (product.grounded().allergyCheck() == null) {
                return reject(MappingFailure.ALLERGY_CHECK_MISSING);
            }
            cards.add(new MermAidAnswer.DrugCard(
                    CARD_ID_PREFIX + source.id(),
                    product.productNameKo(),
                    product.grounded().productNameEn(),
                    ingredients.orElseThrow(),
                    null,
                    nonBlankOrNull(product.grounded().officialDosageKo()),
                    null,
                    product.grounded().warnings(),
                    product.grounded().prescriptionStatus(),
                    product.grounded().allergyCheck(),
                    source.id()));
        }

        MermAidAnswer candidate = new MermAidAnswer(
                MermAidAnswer.SCHEMA_VERSION,
                ANSWER_ID,
                "en",
                dataStatusOf(sources),
                new MermAidAnswer.Urgency(
                        MermAidAnswer.Urgency.Level.UNKNOWN,
                        URGENCY_TITLE,
                        URGENCY_MESSAGE,
                        List.of(),
                        List.of()),
                SUMMARY,
                List.of(),
                List.of(),
                List.copyOf(cards),
                List.of(),
                sources,
                List.of(),
                StructuredOutputFallback.DISCLAIMER);

        List<ViolationCode> violations = answerValidator.validate(candidate, groundedDrugs);
        if (!violations.isEmpty()) {
            Map<ViolationCode, Integer> counts = new EnumMap<>(ViolationCode.class);
            violations.forEach(code -> counts.merge(code, 1, Integer::sum));
            log.warn(
                    "server_authored_answer_rejected total={} codes={}",
                    violations.size(),
                    counts);
            return Optional.empty();
        }
        return Optional.of(candidate);
    }

    private Optional<List<MermAidAnswer.Ingredient>> ingredientsOf(GroundedDrug grounded) {
        if (grounded.ingredientNamesEn() == null) {
            return Optional.empty();
        }
        List<MermAidAnswer.Ingredient> ingredients = new ArrayList<>();
        Set<String> normalizedKeys = new HashSet<>();
        for (String name : grounded.ingredientNamesEn()) {
            String key = ingredientNormalizer.normalizeIdentity(name).key();
            if (key == null || key.isBlank()) {
                return Optional.empty();
            }
            ingredients.add(new MermAidAnswer.Ingredient(null, name, key, null, null));
            normalizedKeys.add(key);
        }
        if (!normalizedKeys.equals(grounded.ingredientKeys())) {
            return Optional.empty();
        }
        return Optional.of(List.copyOf(ingredients));
    }

    private static MermAidAnswer.DataStatus dataStatusOf(List<SourceRef> sources) {
        boolean hasLive =
                sources.stream().anyMatch(source -> source.dataMode() == SourceRef.DataMode.LIVE);
        boolean hasFixture =
                sources.stream().anyMatch(source -> source.dataMode() == SourceRef.DataMode.FIXTURE);
        if (hasLive && hasFixture) {
            return MermAidAnswer.DataStatus.MIXED;
        }
        return hasFixture ? MermAidAnswer.DataStatus.FIXTURE : MermAidAnswer.DataStatus.LIVE;
    }

    private static String nonBlankOrNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Optional<MermAidAnswer> reject(MappingFailure failure) {
        log.warn("server_authored_answer_mapping_rejected code={}", failure);
        return Optional.empty();
    }

    private record ProductRecord(String productNameKo, GroundedDrug grounded) {}

    private enum MappingFailure {
        CARDINALITY_MISMATCH,
        SOURCE_ID_MISSING,
        SOURCE_MODE_MISSING,
        DUPLICATE_SOURCE_ID,
        PRODUCT_NAME_MISSING,
        GROUNDED_SOURCE_ID_MISSING,
        GROUNDED_SOURCE_UNKNOWN,
        DUPLICATE_GROUNDED_SOURCE_ID,
        SOURCE_PRODUCT_MISMATCH,
        INGREDIENT_KEYS_MISMATCH,
        ALLERGY_CHECK_MISSING
    }
}
