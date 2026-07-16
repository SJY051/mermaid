package com.mermaid.chat;

import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

/** Untrusted typed claims proposed by the model; none of these values are directly renderable. */
record ModelGeneralExplanationDraft(List<ModelGeneralClaim> claims) {

    ModelGeneralExplanationDraft {
        Objects.requireNonNull(claims, "claims");
        for (Object claim : claims) {
            if (!(claim instanceof ModelGeneralClaim)) {
                throw new IllegalArgumentException("claims must contain typed general claims");
            }
        }
        claims = List.copyOf(claims);
    }

    sealed interface ModelGeneralClaim
            permits ModelSymptomPossibility, ModelTermDefinition, ModelGeneralComparison {}

    record ModelSymptomPossibility(
            GeneralSubject subject, List<GeneralConcept> possibleCauses)
            implements ModelGeneralClaim {

        ModelSymptomPossibility {
            Objects.requireNonNull(subject, "subject");
            possibleCauses = checkedConcepts(possibleCauses, "possibleCauses");
        }
    }

    record ModelTermDefinition(
            GeneralSubject subject,
            DefinitionPredicate predicate,
            List<GeneralConcept> concepts)
            implements ModelGeneralClaim {

        ModelTermDefinition {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(predicate, "predicate");
            concepts = checkedConcepts(concepts, "concepts");
        }
    }

    record ModelGeneralComparison(
            GeneralSubject leftSubject,
            GeneralSubject rightSubject,
            ComparisonDimension dimension,
            List<GeneralConcept> leftConcepts,
            List<GeneralConcept> rightConcepts)
            implements ModelGeneralClaim {

        ModelGeneralComparison {
            Objects.requireNonNull(leftSubject, "leftSubject");
            Objects.requireNonNull(rightSubject, "rightSubject");
            Objects.requireNonNull(dimension, "dimension");
            leftConcepts = checkedConcepts(leftConcepts, "leftConcepts");
            rightConcepts = checkedConcepts(rightConcepts, "rightConcepts");
        }
    }

    enum DefinitionPredicate {
        BODY_RESPONSE_TO,
        BODY_PROCESS_INVOLVING,
        SYMPTOM_OR_SIGN_RELATED_TO,
        MEASUREMENT_OF,
        INFECTION_CAUSED_BY,
        NUTRIENT_RELATED_TO
    }

    enum ComparisonDimension {
        GENERAL_CAUSE_TYPE,
        GENERAL_BODY_PROCESS,
        GENERAL_MEANING
    }

    /** Server-owned launch vocabulary; activation still requires PM and clinical review. */
    enum GeneralSubject {
        MILD_FEVER("mild fever"),
        FEVER("fever", "feverish"),
        COUGH("cough"),
        SORE_THROAT("sore throat", "throat soreness"),
        COMMON_COLD("common cold", "cold"),
        HEADACHE("headache"),
        NAUSEA("nausea"),
        VOMITING("vomiting"),
        DIARRHEA("diarrhea"),
        FATIGUE("fatigue"),
        RUNNY_NOSE("runny nose"),
        NASAL_CONGESTION("nasal congestion"),
        MUSCLE_ACHES("muscle aches", "muscle ache"),
        INFLAMMATION("inflammation"),
        INFECTION("infection"),
        VIRAL_INFECTION("viral infection"),
        BACTERIAL_INFECTION("bacterial infection"),
        INFLUENZA("influenza", "flu"),
        CROHNS_DISEASE("Crohn's disease"),
        TYPE_2_DIABETES("type 2 diabetes"),
        COVID_19("COVID-19"),
        VITAMIN_B12("B12", "vitamin B12"),
        DEHYDRATION("dehydration"),
        BODY_TEMPERATURE("body temperature"),
        BLOOD_PRESSURE("blood pressure"),
        BLOOD_GLUCOSE("blood glucose"),
        OXYGEN_SATURATION("oxygen saturation"),
        IMMUNE_RESPONSE("immune response"),
        ALLERGY("allergy");

        private final String label;
        private final List<String> aliases;

        GeneralSubject(String label, String... additionalAliases) {
            this.label = Objects.requireNonNull(label, "label");
            this.aliases = Stream.concat(Stream.of(label), Stream.of(additionalAliases)).toList();
        }

        String label() {
            return label;
        }

        List<String> aliases() {
            return aliases;
        }
    }

    /** Server-owned vocabulary. The model selects identifiers and never supplies renderable text. */
    enum GeneralConcept {
        COMMON_VIRAL_INFECTIONS("common viral infections"),
        OTHER_COMMON_INFECTIONS("other common infections"),
        IRRITATION("irritation"),
        INJURY("injury"),
        INFLAMMATION("inflammation"),
        VIRUSES("viruses"),
        BACTERIA("bacteria"),
        IMMUNE_SYSTEM_ACTIVITY("immune system activity"),
        BODY_TEMPERATURE("body temperature"),
        BODY_HEAT_LEVEL("the body's heat level"),
        BLOOD_VESSEL_PRESSURE("pressure in blood vessels"),
        BLOOD_GLUCOSE_LEVEL("glucose level in blood"),
        BLOOD_OXYGEN_LEVEL("oxygen level in blood"),
        RESPIRATORY_SYSTEM("the respiratory system"),
        DIGESTIVE_SYSTEM("the digestive system"),
        BLOOD_GLUCOSE_REGULATION("blood glucose regulation"),
        BLOOD_CELL_FORMATION("blood cell formation"),
        NERVOUS_SYSTEM_FUNCTION("nervous system function"),
        FLUID_BALANCE("fluid balance"),
        AIRWAY_IRRITATION("airway irritation");

        private final String label;

        GeneralConcept(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }
    }

    private static List<GeneralConcept> checkedConcepts(
            List<GeneralConcept> concepts, String fieldName) {
        Objects.requireNonNull(concepts, fieldName);
        for (Object concept : concepts) {
            if (!(concept instanceof GeneralConcept)) {
                throw new IllegalArgumentException(fieldName + " must contain concept identifiers");
            }
        }
        return List.copyOf(concepts);
    }
}
