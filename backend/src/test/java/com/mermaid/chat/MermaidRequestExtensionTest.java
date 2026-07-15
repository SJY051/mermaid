package com.mermaid.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mermaid.chat.MermaidRequestExtension.StructuredExclusions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The structured allergen channel's completeness contract (#62 P0, fifth finding).
 *
 * <p>{@code exclude_ingredients} means "the complete list this session must avoid". The parser
 * bounds it (ten entries, 100 chars each), so a real entry the bounds force it to drop makes the
 * held list incomplete — and an incomplete list must fail closed at the gate, never authorize
 * retrieval. These tests pin the signal; the gate test lives in {@code DrugContextRetrieverTest}.
 */
class MermaidRequestExtensionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode requestWithExclusions(String... entries) {
        ObjectNode request = mapper.createObjectNode();
        ArrayNode list = request.putObject("mermaid").putArray("exclude_ingredients");
        for (String entry : entries) {
            list.add(entry);
        }
        return request;
    }

    private ObjectNode requestWithUnverified(String... entries) {
        ObjectNode request = mapper.createObjectNode();
        ArrayNode list = request.putObject("mermaid").putArray("unverified_allergens");
        for (String entry : entries) {
            list.add(entry);
        }
        return request;
    }

    @Test
    @DisplayName("an eleventh entry flags the list incomplete instead of vanishing")
    void eleventhEntryFlagsIncomplete() {
        String[] eleven = new String[11];
        for (int i = 0; i < 11; i++) {
            eleven[i] = "ingredient-" + i;
        }

        StructuredExclusions parsed =
                MermaidRequestExtension.excludedIngredients(requestWithExclusions(eleven));

        assertThat(parsed.terms()).hasSize(10);
        assertThat(parsed.incomplete())
                .as("the dropped allergen is in neither terms nor unresolved — only this flag"
                        + " tells the gate the avoided set is not the user's list")
                .isTrue();
    }

    @Test
    @DisplayName("an over-length entry flags the list incomplete")
    void overlengthEntryFlagsIncomplete() {
        StructuredExclusions parsed = MermaidRequestExtension.excludedIngredients(
                requestWithExclusions("ibuprofen", "x".repeat(101)));

        assertThat(parsed.terms()).containsExactly("ibuprofen");
        assertThat(parsed.incomplete()).isTrue();
    }

    @Test
    @DisplayName("a non-textual entry flags the list incomplete — unreadable is not empty")
    void nonTextualEntryFlagsIncomplete() {
        // A client bug that serializes an allergen row as an object ({"name":"ibuprofen"})
        // reaches asText("") as a blank string. Blank means "no allergen"; this entry may well
        // BE one. Anything we cannot read makes the held list not-the-user's-list.
        ObjectNode request = requestWithExclusions("aspirin");
        ((ArrayNode) request.path("mermaid").path("exclude_ingredients"))
                .addObject()
                .put("name", "ibuprofen");

        StructuredExclusions parsed = MermaidRequestExtension.excludedIngredients(request);

        assertThat(parsed.terms()).containsExactly("aspirin");
        assertThat(parsed.incomplete()).isTrue();
    }

    @Test
    @DisplayName("blank entries carry no allergen, so dropping them is not incompleteness")
    void blankEntriesAreNotALoss() {
        StructuredExclusions parsed = MermaidRequestExtension.excludedIngredients(
                requestWithExclusions("ibuprofen", "", "   "));

        assertThat(parsed.terms()).containsExactly("ibuprofen");
        assertThat(parsed.incomplete()).isFalse();
    }

    @Test
    @DisplayName("a list within bounds is complete")
    void inBoundsListIsComplete() {
        StructuredExclusions parsed = MermaidRequestExtension.excludedIngredients(
                requestWithExclusions("ibuprofen", "aspirin"));

        assertThat(parsed.terms()).containsExactly("ibuprofen", "aspirin");
        assertThat(parsed.incomplete()).isFalse();
    }

    @Test
    @DisplayName("no extension at all is an empty, complete list")
    void absentFieldIsCompleteAndEmpty() {
        StructuredExclusions parsed =
                MermaidRequestExtension.excludedIngredients(mapper.createObjectNode());

        assertThat(parsed.terms()).isEmpty();
        assertThat(parsed.incomplete()).isFalse();
    }

    @Test
    @DisplayName("a present field in the wrong shape flags incomplete — at every level")
    void wrongShapeAtAnyLevelFlagsIncomplete() {
        // "exclude_ingredients": "Ibuprofen" — a mangled list whose one allergen we cannot read
        // as a list. Returning NONE here would let "can I take 부루펜?" retrieve unguarded.
        ObjectNode stringField = mapper.createObjectNode();
        stringField.putObject("mermaid").put("exclude_ingredients", "Ibuprofen");
        assertThat(MermaidRequestExtension.excludedIngredients(stringField).incomplete()).isTrue();

        // "exclude_ingredients": {"name": "Ibuprofen"} — same loss, object shape.
        ObjectNode objectField = mapper.createObjectNode();
        objectField.putObject("mermaid").putObject("exclude_ingredients").put("name", "Ibuprofen");
        assertThat(MermaidRequestExtension.excludedIngredients(objectField).incomplete()).isTrue();

        // "mermaid": "exclude ibuprofen" — the whole extension mangled; it may hold a list.
        ObjectNode mangledExtension = mapper.createObjectNode();
        mangledExtension.put("mermaid", "exclude ibuprofen");
        assertThat(MermaidRequestExtension.excludedIngredients(mangledExtension).incomplete())
                .isTrue();
    }

    @Test
    @DisplayName("an explicit JSON null is the idiom for 'none', not a mangled list")
    void explicitNullMeansNone() {
        ObjectNode nullField = mapper.createObjectNode();
        nullField.putObject("mermaid").putNull("exclude_ingredients");
        StructuredExclusions parsed = MermaidRequestExtension.excludedIngredients(nullField);

        assertThat(parsed.terms()).isEmpty();
        assertThat(parsed.incomplete()).isFalse();
    }

    @Test
    @DisplayName("unverified allergens use the same shape, count, length, and blank rules")
    void unverifiedAllergensUseTheSameCompletenessRules() {
        ObjectNode request = requestWithUnverified("Yellow dye", "", "x".repeat(101));
        ((ArrayNode) request.path("mermaid").path("unverified_allergens"))
                .addObject()
                .put("name", "latex");

        StructuredExclusions parsed = MermaidRequestExtension.excludedIngredients(request);

        assertThat(parsed.terms()).isEmpty();
        assertThat(parsed.unverifiedTerms()).containsExactly("Yellow dye");
        assertThat(parsed.incomplete()).isTrue();
    }
}
