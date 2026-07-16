package com.mermaid.chat;

import com.mermaid.chat.GeneralExplanationSemanticVerifier.GeneralComparisonReview;
import com.mermaid.chat.GeneralExplanationSemanticVerifier.SemanticClaim;
import com.mermaid.chat.GeneralExplanationSemanticVerifier.SemanticReviewDecision;
import com.mermaid.chat.GeneralExplanationSemanticVerifier.SemanticReviewInput;
import com.mermaid.chat.GeneralExplanationSemanticVerifier.SemanticReviewUnavailableException;
import com.mermaid.chat.GeneralExplanationSemanticVerifier.SymptomPossibilityReview;
import com.mermaid.chat.GeneralExplanationSemanticVerifier.TermDefinitionReview;
import com.mermaid.chat.ModelGeneralExplanationDraft.ComparisonDimension;
import com.mermaid.chat.ModelGeneralExplanationDraft.DefinitionPredicate;
import com.mermaid.chat.ModelGeneralExplanationDraft.GeneralConcept;
import com.mermaid.chat.ModelGeneralExplanationDraft.GeneralSubject;
import com.mermaid.chat.ModelGeneralExplanationDraft.ModelGeneralClaim;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/** Admits typed claims, obtains semantic approval, and alone can mint renderable explanations. */
final class GeneralExplanationPipeline {

    private static final int MAX_CLAIMS = 3;
    private static final int MAX_CONCEPTS = 3;
    private static final Map<GeneralSubject, Set<GeneralConcept>> SYMPTOM_RULES =
            symptomRules();
    private static final Map<GeneralSubject, DefinitionRule> DEFINITION_RULES =
            definitionRules();
    private static final Map<ComparisonKey, ComparisonRule> COMPARISON_RULES =
            comparisonRules();

    private final GeneralExplanationSemanticVerifier semanticVerifier;

    GeneralExplanationPipeline(GeneralExplanationSemanticVerifier semanticVerifier) {
        this.semanticVerifier = Objects.requireNonNull(semanticVerifier, "semanticVerifier");
    }

    Outcome process(
            String latestUserTurn,
            ModelGeneralExplanationDraft draft,
            Set<String> currentMedicineTerms,
            ResponsePlan.ResponseMode mode) {
        Objects.requireNonNull(latestUserTurn, "latestUserTurn");
        Objects.requireNonNull(draft, "draft");
        Objects.requireNonNull(currentMedicineTerms, "currentMedicineTerms");
        Objects.requireNonNull(mode, "mode");
        if (!isRenderableMode(mode)) {
            return Outcome.failed(Failure.MODE_NOT_RENDERABLE);
        }

        Optional<AdmittedAst> admitted = admit(latestUserTurn, draft, currentMedicineTerms);
        if (admitted.isEmpty()) {
            return Outcome.failed(Failure.SHAPE_REJECTED);
        }

        AdmittedAst ast = admitted.orElseThrow();
        SemanticReviewDecision review;
        try {
            review = semanticVerifier.review(ast.reviewInput());
        } catch (SemanticReviewUnavailableException unavailable) {
            return Outcome.failed(Failure.SEMANTIC_UNAVAILABLE);
        }
        if (review == null || !review.allowsRendering()) {
            return Outcome.failed(Failure.SEMANTIC_REJECTED);
        }
        return Outcome.rendered(new RenderedGeneralExplanation(render(ast)));
    }

    private static Optional<AdmittedAst> admit(
            String latestUserTurn,
            ModelGeneralExplanationDraft draft,
            Set<String> currentMedicineTerms) {
        if (draft.claims().isEmpty() || draft.claims().size() > MAX_CLAIMS) {
            return Optional.empty();
        }
        String normalizedUserTurn = normalizeText(latestUserTurn);
        Set<String> medicineTerms = normalizeMedicineTerms(currentMedicineTerms);
        List<AdmittedClaim> claims = new ArrayList<>();
        for (ModelGeneralClaim claim : draft.claims()) {
            Optional<AdmittedClaim> admitted = admitClaim(
                    claim, normalizedUserTurn, medicineTerms);
            if (admitted.isEmpty()) {
                return Optional.empty();
            }
            claims.add(admitted.orElseThrow());
        }
        return Optional.of(new AdmittedAst(claims));
    }

    private static Optional<AdmittedClaim> admitClaim(
            ModelGeneralClaim claim,
            String normalizedUserTurn,
            Set<String> medicineTerms) {
        if (claim instanceof ModelGeneralExplanationDraft.ModelSymptomPossibility possibility) {
            Set<GeneralConcept> allowedConcepts = SYMPTOM_RULES.get(possibility.subject());
            if (!subjectBound(
                            possibility.subject(),
                            normalizedUserTurn,
                            medicineTerms,
                            SYMPTOM_RULES.keySet())
                    || !conceptsAllowed(
                            possibility.possibleCauses(), allowedConcepts)) {
                return Optional.empty();
            }
            return Optional.of(new AdmittedSymptomPossibility(
                    possibility.subject(), possibility.possibleCauses()));
        }
        if (claim instanceof ModelGeneralExplanationDraft.ModelTermDefinition definition) {
            DefinitionRule rule = DEFINITION_RULES.get(definition.subject());
            if (!subjectBound(
                            definition.subject(),
                            normalizedUserTurn,
                            medicineTerms,
                            DEFINITION_RULES.keySet())
                    || rule == null
                    || definition.predicate() != rule.predicate()
                    || !conceptsExactlyMatch(definition.concepts(), rule.concepts())) {
                return Optional.empty();
            }
            return Optional.of(new AdmittedTermDefinition(
                    definition.subject(), definition.predicate(), definition.concepts()));
        }
        if (claim instanceof ModelGeneralExplanationDraft.ModelGeneralComparison comparison) {
            ComparisonRule rule = COMPARISON_RULES.get(new ComparisonKey(
                    comparison.leftSubject(),
                    comparison.rightSubject(),
                    comparison.dimension()));
            if (!subjectBound(
                            comparison.leftSubject(),
                            normalizedUserTurn,
                            medicineTerms,
                            DEFINITION_RULES.keySet())
                    || !subjectBound(
                            comparison.rightSubject(),
                            normalizedUserTurn,
                            medicineTerms,
                            DEFINITION_RULES.keySet())
                    || rule == null
                    || !comparison.leftConcepts().equals(rule.leftConcepts())
                    || !comparison.rightConcepts().equals(rule.rightConcepts())) {
                return Optional.empty();
            }
            return Optional.of(new AdmittedGeneralComparison(
                    comparison.leftSubject(),
                    comparison.rightSubject(),
                    comparison.dimension(),
                    comparison.leftConcepts(),
                    comparison.rightConcepts()));
        }
        return Optional.empty();
    }

    private static boolean subjectBound(
            GeneralSubject subject,
            String normalizedUserTurn,
            Set<String> medicineTerms,
            Set<GeneralSubject> allowedSubjects) {
        if (!allowedSubjects.contains(subject)) {
            return false;
        }
        for (String alias : subject.aliases()) {
            String normalizedAlias = normalizeText(alias);
            if (containsExactPhrase(normalizedUserTurn, normalizedAlias)
                    && !containsMedicineTerm(normalizedAlias, medicineTerms)) {
                return true;
            }
        }
        return false;
    }

    private static boolean conceptsAllowed(
            List<GeneralConcept> concepts, Set<GeneralConcept> allowed) {
        if (allowed == null
                || concepts.isEmpty()
                || concepts.size() > MAX_CONCEPTS
                || new HashSet<>(concepts).size() != concepts.size()) {
            return false;
        }
        return allowed.containsAll(concepts);
    }

    private static boolean conceptsExactlyMatch(
            List<GeneralConcept> concepts, Set<GeneralConcept> serverTuple) {
        return concepts.size() == serverTuple.size()
                && new HashSet<>(concepts).equals(serverTuple);
    }

    private static boolean containsMedicineTerm(String mention, Set<String> medicineTerms) {
        for (String medicineTerm : medicineTerms) {
            if (containsExactPhrase(mention, medicineTerm)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsExactPhrase(String text, String phrase) {
        Pattern exactPhrase = Pattern.compile(
                "(?i)(?<![A-Za-z0-9])" + Pattern.quote(phrase) + "(?![A-Za-z0-9])");
        return exactPhrase.matcher(text).find();
    }

    private static Set<String> normalizeMedicineTerms(Set<String> currentMedicineTerms) {
        Set<String> normalized = new HashSet<>();
        for (String term : currentMedicineTerms) {
            if (term != null && !containsDisallowedCodePoint(term)) {
                String value = normalizeText(term);
                if (!value.isBlank()) {
                    normalized.add(value);
                }
            }
        }
        return Set.copyOf(normalized);
    }

    private static String render(AdmittedAst ast) {
        List<String> sentences = new ArrayList<>();
        for (AdmittedClaim claim : ast.claims()) {
            sentences.add(renderClaim(claim));
        }
        return String.join(" ", sentences);
    }

    private static String renderClaim(AdmittedClaim claim) {
        if (claim instanceof AdmittedSymptomPossibility possibility) {
            return capitalize(possibility.subject().label())
                    + " can happen for many reasons. General possibilities include "
                    + joinConcepts(possibility.possibleCauses())
                    + ". Symptoms alone cannot identify the cause or confirm a diagnosis.";
        }
        if (claim instanceof AdmittedTermDefinition definition) {
            String subject = capitalize(definition.subject().label());
            String concepts = joinConcepts(definition.concepts());
            return switch (definition.predicate()) {
                case BODY_RESPONSE_TO -> subject
                        + " is a medical term for a body response to "
                        + concepts
                        + ". This is a general definition, not a diagnosis.";
                case BODY_PROCESS_INVOLVING -> subject
                        + " describes a body process involving "
                        + concepts
                        + ". This general explanation does not determine whether it applies to a person.";
                case SYMPTOM_OR_SIGN_RELATED_TO -> subject
                        + " is a symptom or sign related to "
                        + concepts
                        + ". This is a general definition, not a diagnosis.";
                case MEASUREMENT_OF -> subject
                        + " is a measurement of "
                        + concepts
                        + ". This general explanation does not interpret a person's result.";
                case INFECTION_CAUSED_BY -> subject
                        + " describes an infection caused by "
                        + concepts
                        + ". This is a general definition, not a diagnosis.";
                case NUTRIENT_RELATED_TO -> subject
                        + " is a nutrient related to "
                        + concepts
                        + ". This is a general definition, not personal nutrition advice.";
            };
        }
        if (claim instanceof AdmittedGeneralComparison comparison) {
            String left = capitalize(comparison.leftSubject().label());
            String right = comparison.rightSubject().label();
            String dimension = switch (comparison.dimension()) {
                case GENERAL_CAUSE_TYPE -> "their general cause type";
                case GENERAL_BODY_PROCESS -> "the general body process involved";
                case GENERAL_MEANING -> "their general meaning";
            };
            return left
                    + " and "
                    + right
                    + " differ in "
                    + dimension
                    + ". "
                    + left
                    + " is associated with "
                    + joinConcepts(comparison.leftConcepts())
                    + ", while "
                    + right
                    + " is associated with "
                    + joinConcepts(comparison.rightConcepts())
                    + ". This distinction cannot determine which applies to a person.";
        }
        throw new IllegalArgumentException("unsupported general explanation claim");
    }

    private static String joinConcepts(List<GeneralConcept> concepts) {
        List<String> labels = concepts.stream().map(GeneralConcept::label).toList();
        if (labels.size() == 1) {
            return labels.getFirst();
        }
        if (labels.size() == 2) {
            return labels.get(0) + " and " + labels.get(1);
        }
        return String.join(", ", labels.subList(0, labels.size() - 1))
                + ", and "
                + labels.getLast();
    }

    private static String capitalize(String value) {
        if (value.isEmpty()) {
            return value;
        }
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static String normalizeText(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .replace('\u2018', '\'')
                .replace('\u2019', '\'')
                .replace('\u2010', '-')
                .replace('\u2011', '-')
                .replace('\u2012', '-')
                .replace('\u2013', '-')
                .replace('\u2212', '-')
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean containsDisallowedCodePoint(String value) {
        return value.codePoints().anyMatch(codePoint -> {
            int type = Character.getType(codePoint);
            return type == Character.CONTROL
                    || type == Character.FORMAT
                    || type == Character.LINE_SEPARATOR
                    || type == Character.PARAGRAPH_SEPARATOR
                    || type == Character.SURROGATE
                    || type == Character.PRIVATE_USE;
        });
    }

    private static boolean isRenderableMode(ResponsePlan.ResponseMode mode) {
        return mode == ResponsePlan.ResponseMode.T1_ANSWER_GENERAL_OR_LOCATE_CARE
                || mode == ResponsePlan.ResponseMode.T2_ANSWER_WITH_CONSULTATION;
    }

    private static Map<GeneralSubject, Set<GeneralConcept>> symptomRules() {
        Map<GeneralSubject, Set<GeneralConcept>> rules = new EnumMap<>(GeneralSubject.class);
        rules.put(
                GeneralSubject.MILD_FEVER,
                EnumSet.of(
                        GeneralConcept.COMMON_VIRAL_INFECTIONS,
                        GeneralConcept.OTHER_COMMON_INFECTIONS,
                        GeneralConcept.INFLAMMATION,
                        GeneralConcept.IMMUNE_SYSTEM_ACTIVITY));
        rules.put(GeneralSubject.FEVER, rules.get(GeneralSubject.MILD_FEVER));
        rules.put(
                GeneralSubject.COUGH,
                EnumSet.of(
                        GeneralConcept.COMMON_VIRAL_INFECTIONS,
                        GeneralConcept.OTHER_COMMON_INFECTIONS,
                        GeneralConcept.AIRWAY_IRRITATION));
        rules.put(
                GeneralSubject.SORE_THROAT,
                EnumSet.of(
                        GeneralConcept.COMMON_VIRAL_INFECTIONS,
                        GeneralConcept.OTHER_COMMON_INFECTIONS,
                        GeneralConcept.IRRITATION));
        rules.put(
                GeneralSubject.COMMON_COLD,
                EnumSet.of(GeneralConcept.COMMON_VIRAL_INFECTIONS));
        rules.put(
                GeneralSubject.HEADACHE,
                EnumSet.of(
                        GeneralConcept.OTHER_COMMON_INFECTIONS,
                        GeneralConcept.FLUID_BALANCE));
        Set<GeneralConcept> digestiveSymptoms = EnumSet.of(
                GeneralConcept.OTHER_COMMON_INFECTIONS,
                GeneralConcept.IRRITATION,
                GeneralConcept.FLUID_BALANCE);
        rules.put(GeneralSubject.NAUSEA, digestiveSymptoms);
        rules.put(GeneralSubject.VOMITING, digestiveSymptoms);
        rules.put(GeneralSubject.DIARRHEA, digestiveSymptoms);
        rules.put(
                GeneralSubject.FATIGUE,
                EnumSet.of(
                        GeneralConcept.COMMON_VIRAL_INFECTIONS,
                        GeneralConcept.OTHER_COMMON_INFECTIONS,
                        GeneralConcept.IMMUNE_SYSTEM_ACTIVITY,
                        GeneralConcept.FLUID_BALANCE));
        Set<GeneralConcept> nasalSymptoms = EnumSet.of(
                GeneralConcept.COMMON_VIRAL_INFECTIONS,
                GeneralConcept.IRRITATION,
                GeneralConcept.AIRWAY_IRRITATION);
        rules.put(GeneralSubject.RUNNY_NOSE, nasalSymptoms);
        rules.put(GeneralSubject.NASAL_CONGESTION, nasalSymptoms);
        rules.put(
                GeneralSubject.MUSCLE_ACHES,
                EnumSet.of(
                        GeneralConcept.COMMON_VIRAL_INFECTIONS,
                        GeneralConcept.INJURY,
                        GeneralConcept.INFLAMMATION));
        rules.replaceAll((subject, concepts) -> Set.copyOf(concepts));
        return Map.copyOf(rules);
    }

    private static Map<GeneralSubject, DefinitionRule> definitionRules() {
        Map<GeneralSubject, DefinitionRule> rules = new EnumMap<>(GeneralSubject.class);
        rules.put(
                GeneralSubject.INFLAMMATION,
                new DefinitionRule(
                        DefinitionPredicate.BODY_RESPONSE_TO,
                        EnumSet.of(GeneralConcept.IRRITATION, GeneralConcept.INJURY)));
        rules.put(
                GeneralSubject.INFECTION,
                new DefinitionRule(
                        DefinitionPredicate.INFECTION_CAUSED_BY,
                        EnumSet.of(GeneralConcept.VIRUSES, GeneralConcept.BACTERIA)));
        rules.put(
                GeneralSubject.VIRAL_INFECTION,
                new DefinitionRule(
                        DefinitionPredicate.INFECTION_CAUSED_BY,
                        EnumSet.of(GeneralConcept.VIRUSES)));
        rules.put(
                GeneralSubject.BACTERIAL_INFECTION,
                new DefinitionRule(
                        DefinitionPredicate.INFECTION_CAUSED_BY,
                        EnumSet.of(GeneralConcept.BACTERIA)));
        rules.put(
                GeneralSubject.INFLUENZA,
                new DefinitionRule(
                        DefinitionPredicate.INFECTION_CAUSED_BY,
                        EnumSet.of(GeneralConcept.VIRUSES)));
        rules.put(
                GeneralSubject.CROHNS_DISEASE,
                new DefinitionRule(
                        DefinitionPredicate.BODY_PROCESS_INVOLVING,
                        EnumSet.of(
                                GeneralConcept.INFLAMMATION,
                                GeneralConcept.DIGESTIVE_SYSTEM)));
        rules.put(
                GeneralSubject.TYPE_2_DIABETES,
                new DefinitionRule(
                        DefinitionPredicate.BODY_PROCESS_INVOLVING,
                        EnumSet.of(GeneralConcept.BLOOD_GLUCOSE_REGULATION)));
        rules.put(
                GeneralSubject.COVID_19,
                new DefinitionRule(
                        DefinitionPredicate.INFECTION_CAUSED_BY,
                        EnumSet.of(GeneralConcept.VIRUSES)));
        rules.put(
                GeneralSubject.VITAMIN_B12,
                new DefinitionRule(
                        DefinitionPredicate.NUTRIENT_RELATED_TO,
                        EnumSet.of(GeneralConcept.BLOOD_CELL_FORMATION)));
        rules.put(
                GeneralSubject.DEHYDRATION,
                new DefinitionRule(
                        DefinitionPredicate.BODY_PROCESS_INVOLVING,
                        EnumSet.of(GeneralConcept.FLUID_BALANCE)));
        rules.put(
                GeneralSubject.BODY_TEMPERATURE,
                new DefinitionRule(
                        DefinitionPredicate.MEASUREMENT_OF,
                        EnumSet.of(GeneralConcept.BODY_HEAT_LEVEL)));
        rules.put(
                GeneralSubject.BLOOD_PRESSURE,
                new DefinitionRule(
                        DefinitionPredicate.MEASUREMENT_OF,
                        EnumSet.of(GeneralConcept.BLOOD_VESSEL_PRESSURE)));
        rules.put(
                GeneralSubject.BLOOD_GLUCOSE,
                new DefinitionRule(
                        DefinitionPredicate.MEASUREMENT_OF,
                        EnumSet.of(GeneralConcept.BLOOD_GLUCOSE_LEVEL)));
        rules.put(
                GeneralSubject.OXYGEN_SATURATION,
                new DefinitionRule(
                        DefinitionPredicate.MEASUREMENT_OF,
                        EnumSet.of(GeneralConcept.BLOOD_OXYGEN_LEVEL)));
        rules.put(
                GeneralSubject.IMMUNE_RESPONSE,
                new DefinitionRule(
                        DefinitionPredicate.BODY_PROCESS_INVOLVING,
                        EnumSet.of(GeneralConcept.IMMUNE_SYSTEM_ACTIVITY)));
        rules.put(
                GeneralSubject.ALLERGY,
                new DefinitionRule(
                        DefinitionPredicate.BODY_PROCESS_INVOLVING,
                        EnumSet.of(GeneralConcept.IMMUNE_SYSTEM_ACTIVITY)));
        return Map.copyOf(rules);
    }

    private static Map<ComparisonKey, ComparisonRule> comparisonRules() {
        Map<ComparisonKey, ComparisonRule> rules = new java.util.HashMap<>();
        rules.put(
                new ComparisonKey(
                        GeneralSubject.VIRAL_INFECTION,
                        GeneralSubject.BACTERIAL_INFECTION,
                        ComparisonDimension.GENERAL_CAUSE_TYPE),
                new ComparisonRule(
                        List.of(GeneralConcept.VIRUSES), List.of(GeneralConcept.BACTERIA)));
        rules.put(
                new ComparisonKey(
                        GeneralSubject.BACTERIAL_INFECTION,
                        GeneralSubject.VIRAL_INFECTION,
                        ComparisonDimension.GENERAL_CAUSE_TYPE),
                new ComparisonRule(
                        List.of(GeneralConcept.BACTERIA), List.of(GeneralConcept.VIRUSES)));
        return Map.copyOf(rules);
    }

    private record DefinitionRule(
            DefinitionPredicate predicate, Set<GeneralConcept> concepts) {

        private DefinitionRule {
            concepts = Set.copyOf(concepts);
        }
    }

    private record ComparisonKey(
            GeneralSubject left,
            GeneralSubject right,
            ComparisonDimension dimension) {}

    private record ComparisonRule(
            List<GeneralConcept> leftConcepts, List<GeneralConcept> rightConcepts) {

        private ComparisonRule {
            leftConcepts = List.copyOf(leftConcepts);
            rightConcepts = List.copyOf(rightConcepts);
        }
    }

    private record AdmittedAst(List<AdmittedClaim> claims) {

        private AdmittedAst {
            claims = List.copyOf(claims);
        }

        private SemanticReviewInput reviewInput() {
            return new SemanticReviewInput(claims.stream()
                    .map(AdmittedClaim::reviewClaim)
                    .toList());
        }
    }

    private sealed interface AdmittedClaim
            permits AdmittedSymptomPossibility,
                    AdmittedTermDefinition,
                    AdmittedGeneralComparison {

        SemanticClaim reviewClaim();
    }

    private record AdmittedSymptomPossibility(
            GeneralSubject subject, List<GeneralConcept> possibleCauses)
            implements AdmittedClaim {

        private AdmittedSymptomPossibility {
            possibleCauses = List.copyOf(possibleCauses);
        }

        @Override
        public SemanticClaim reviewClaim() {
            return new SymptomPossibilityReview(subject, possibleCauses);
        }
    }

    private record AdmittedTermDefinition(
            GeneralSubject subject,
            DefinitionPredicate predicate,
            List<GeneralConcept> concepts)
            implements AdmittedClaim {

        private AdmittedTermDefinition {
            concepts = List.copyOf(concepts);
        }

        @Override
        public SemanticClaim reviewClaim() {
            return new TermDefinitionReview(subject, predicate, concepts);
        }
    }

    private record AdmittedGeneralComparison(
            GeneralSubject leftSubject,
            GeneralSubject rightSubject,
            ComparisonDimension dimension,
            List<GeneralConcept> leftConcepts,
            List<GeneralConcept> rightConcepts)
            implements AdmittedClaim {

        private AdmittedGeneralComparison {
            leftConcepts = List.copyOf(leftConcepts);
            rightConcepts = List.copyOf(rightConcepts);
        }

        @Override
        public SemanticClaim reviewClaim() {
            return new GeneralComparisonReview(
                    leftSubject, rightSubject, dimension, leftConcepts, rightConcepts);
        }
    }

    record Outcome(Optional<RenderedGeneralExplanation> explanation, Failure failure) {

        Outcome {
            Objects.requireNonNull(explanation, "explanation");
            if (explanation.isPresent() == (failure != null)) {
                throw new IllegalArgumentException(
                        "exactly one of explanation and failure must be present");
            }
        }

        private static Outcome rendered(RenderedGeneralExplanation explanation) {
            return new Outcome(Optional.of(explanation), null);
        }

        private static Outcome failed(Failure failure) {
            return new Outcome(Optional.empty(), Objects.requireNonNull(failure));
        }
    }

    static final class RenderedGeneralExplanation {

        private final String summary;

        private RenderedGeneralExplanation(String summary) {
            this.summary = Objects.requireNonNull(summary, "summary");
        }

        String summary() {
            return summary;
        }
    }

    enum Failure {
        SHAPE_REJECTED,
        SEMANTIC_REJECTED,
        SEMANTIC_UNAVAILABLE,
        MODE_NOT_RENDERABLE
    }
}
