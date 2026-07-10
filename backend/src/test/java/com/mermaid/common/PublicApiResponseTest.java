package com.mermaid.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Runs against the responses the real APIs returned on 2026-07-10, not against invented JSON.
 *
 * <p>Every assertion here corresponds to something that surprised us when we finally called the
 * services with a real key. See {@code src/test/resources/fixtures/README.md}.
 */
class PublicApiResponseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode fixture(String name) throws Exception {
        try (InputStream in = PublicApiResponseTest.class.getResourceAsStream("/fixtures/" + name)) {
            assertThat(in).as("fixture %s must exist", name).isNotNull();
            return MAPPER.readTree(in);
        }
    }

    @Nested
    @DisplayName("the two envelope shapes")
    class Envelopes {

        @Test
        @DisplayName("약국: wrapped in `response`")
        void pharmacyIsWrapped() throws Exception {
            PublicApiResponse r = PublicApiResponse.of(fixture("pharmacy.json")).requireOk();

            assertThat(r.resultCode()).isEqualTo("00");
            assertThat(r.totalCount()).isGreaterThan(0);
            assertThat(r.items()).isNotEmpty();
        }

        @Test
        @DisplayName("식약처: header and body at the top level, no `response` wrapper")
        void mfdsIsNotWrapped() throws Exception {
            for (String f : List.of("easydrug.json", "permission.json", "dur_usjnt.json", "dur_age.json")) {
                PublicApiResponse r = PublicApiResponse.of(fixture(f)).requireOk();
                assertThat(r.items()).as("%s should yield rows", f).isNotEmpty();
            }
        }
    }

    @Nested
    @DisplayName("the shape of `items`")
    class Items {

        @Test
        @DisplayName("a single result arrives as an object and still comes back as a list")
        void singleResultIsAnObject() throws Exception {
            // pharmacy_basis is a single HPID lookup: items.item is `{...}`, not `[{...}]`.
            JsonNode raw = fixture("pharmacy_basis.json");
            JsonNode item = raw.path("response").path("body").path("items").path("item");
            assertThat(item.isObject()).as("fixture really is an object, not an array").isTrue();

            assertThat(PublicApiResponse.of(raw).items()).hasSize(1);
        }

        @Test
        @DisplayName("many results arrive as an array")
        void manyResultsAreAnArray() throws Exception {
            assertThat(PublicApiResponse.of(fixture("pharmacy.json")).items()).hasSizeGreaterThan(1);
        }
    }

    @Nested
    @DisplayName("mixed field types inside one object")
    class MixedTypes {

        @Test
        @DisplayName("dutyTime1s is a string and dutyTime1c is a number — both read as text")
        void readsBothAsText() throws Exception {
            JsonNode row = PublicApiResponse.of(fixture("pharmacy_basis.json")).items().get(0);

            assertThat(row.path("dutyTime1s").isTextual()).as("the API really sends a string here").isTrue();
            assertThat(row.path("dutyTime1c").isNumber()).as("…and a number here").isTrue();

            assertThat(PublicApiResponse.text(row, "dutyTime1s")).isEqualTo("0900");
            assertThat(PublicApiResponse.text(row, "dutyTime1c")).isEqualTo("1900");
        }

        @Test
        @DisplayName("an absent field is null, not the string \"null\"")
        void absentIsNull() throws Exception {
            JsonNode row = PublicApiResponse.of(fixture("pharmacy_basis.json")).items().get(0);

            // This pharmacy closes on Sunday, so the API omits dutyTime7s entirely.
            assertThat(row.has("dutyTime7s")).isFalse();
            assertThat(PublicApiResponse.text(row, "dutyTime7s")).isNull();
            assertThat(PublicApiResponse.number(row, "dutyTime7s")).isNull();
        }

        @Test
        @DisplayName("number() copes with a numeric string")
        void numberFromString() throws Exception {
            JsonNode row = PublicApiResponse.of(fixture("pharmacy.json")).items().get(0);

            // `distance` is in kilometres — 0.14 means 140 metres, not 140 km.
            Double distance = PublicApiResponse.number(row, "distance");
            assertThat(distance).isNotNull().isLessThan(1.0);

            assertThat(PublicApiResponse.number(row, "latitude")).isBetween(33.0, 39.0);
        }
    }

    @Nested
    @DisplayName("error envelopes arrive with HTTP 200")
    class Errors {

        @Test
        @DisplayName("a non-00 resultCode throws rather than yielding empty rows")
        void badResultCodeThrows() throws Exception {
            JsonNode bad =
                    MAPPER.readTree(
                            """
                            {"response":{"header":{"resultCode":"30","resultMsg":"SERVICE KEY IS NOT REGISTERED ERROR."},
                             "body":{"items":{},"totalCount":0}}}
                            """);

            assertThatThrownBy(() -> PublicApiResponse.of(bad).requireOk())
                    .isInstanceOf(PublicApiException.class)
                    .hasMessageContaining("resultCode=30");
        }

        @Test
        @DisplayName("an empty body yields no rows rather than an NPE")
        void emptyBody() throws Exception {
            JsonNode empty = MAPPER.readTree("{\"header\":{\"resultCode\":\"00\"},\"body\":{\"totalCount\":0}}");

            PublicApiResponse r = PublicApiResponse.of(empty).requireOk();

            assertThat(r.items()).isEmpty();
            assertThat(r.totalCount()).isZero();
        }

        @Test
        @DisplayName("a null root throws a typed exception")
        void nullRoot() {
            assertThatThrownBy(() -> PublicApiResponse.of(null)).isInstanceOf(PublicApiException.class);
        }
    }

    @Nested
    @DisplayName("ITEM_SEQ is the master join key across all three MFDS services")
    class JoinKey {

        @Test
        void sameProductSameId() throws Exception {
            JsonNode easyDrug = PublicApiResponse.of(fixture("easydrug.json")).items().get(0);
            JsonNode permission = PublicApiResponse.of(fixture("permission_detail.json")).items().get(0);

            String fromEasyDrug = PublicApiResponse.text(easyDrug, "itemSeq");
            String fromPermission = PublicApiResponse.text(permission, "ITEM_SEQ");

            assertThat(fromEasyDrug).isEqualTo(fromPermission).isEqualTo("202005623");
        }

        @Test
        @DisplayName("the permission DETAIL op is where MAIN_INGR_ENG lives — the list op has none")
        void englishIngredientOnlyInDetail() throws Exception {
            JsonNode detail = PublicApiResponse.of(fixture("permission_detail.json")).items().get(0);
            JsonNode list = PublicApiResponse.of(fixture("permission.json")).items().get(0);

            assertThat(PublicApiResponse.text(detail, "MAIN_INGR_ENG")).isNotBlank();
            assertThat(PublicApiResponse.text(detail, "MAIN_ITEM_INGR")).startsWith("[M");

            assertThat(list.has("MAIN_INGR_ENG")).isFalse();
            assertThat(PublicApiResponse.text(list, "ITEM_INGR_NAME")).isNotBlank(); // English, '/'-separated
        }

        @Test
        @DisplayName("DUR's INGR_CODE is a D-code and does NOT join to the permission [M-code]")
        void durCodesDoNotJoin() throws Exception {
            JsonNode dur = PublicApiResponse.of(fixture("dur_usjnt.json")).items().get(0);

            assertThat(PublicApiResponse.text(dur, "INGR_CODE")).startsWith("D");
            // …but DUR's own MAIN_INGR is in the same [M######] space as 허가정보's MAIN_ITEM_INGR.
            assertThat(PublicApiResponse.text(dur, "MAIN_INGR")).startsWith("[M");
        }

        @Test
        @DisplayName("e약은요 carries no ingredient field at all — hence the permission API")
        void easyDrugHasNoIngredients() throws Exception {
            JsonNode row = PublicApiResponse.of(fixture("easydrug.json")).items().get(0);

            assertThat(row.has("MAIN_INGR_ENG")).isFalse();
            assertThat(row.has("ITEM_INGR_NAME")).isFalse();
            assertThat(row.has("MAIN_ITEM_INGR")).isFalse();
            assertThat(PublicApiResponse.text(row, "efcyQesitm")).isNotBlank(); // only narrative text
        }
    }

    @Nested
    @DisplayName("DUR age contraindications hide the threshold in Korean prose")
    class DurAge {

        @Test
        void noStructuredAgeField() throws Exception {
            JsonNode row = PublicApiResponse.of(fixture("dur_age.json")).items().get(0);

            assertThat(row.fieldNames())
                    .toIterable()
                    .as("no field name contains AGE")
                    .noneMatch(n -> n.toUpperCase().contains("AGE"));

            assertThat(PublicApiResponse.text(row, "PROHBT_CONTENT")).contains("세");
        }
    }
}
