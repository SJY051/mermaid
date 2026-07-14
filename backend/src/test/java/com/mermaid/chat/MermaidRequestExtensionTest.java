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
}
