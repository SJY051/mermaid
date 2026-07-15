package com.mermaid.drug;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mermaid.chat.dto.AllergyCheck;
import com.mermaid.common.FixtureLoader;
import com.mermaid.config.DataModeProperties;
import com.mermaid.config.PublicApiProperties;
import com.mermaid.drug.DrugService.RetrievalQuery;
import com.mermaid.drug.DrugService.RetrievedContext;
import com.mermaid.drug.domain.Drug;
import com.mermaid.drug.domain.DurWarning;
import com.mermaid.drug.domain.PrescriptionStatus;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Pass 1 of the RAG flow: which three medicines the model is allowed to talk about.
 *
 * <p>Driven by stub clients rather than fixtures, because fixtures ignore query parameters — every
 * {@code detail()} would return the same product and none of the ranking would be observable. The
 * rows below are shaped after what the live 허가정보 API returned for {@code item_ingr_name=Acetaminophen}
 * on 2026-07-10, including the export-only products and the six-ingredient cold syrup it ranks first.
 */
class DrugRetrievalTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-10T05:00:00Z"), ZoneOffset.UTC);

    private final IngredientNormalizer normalizer = new IngredientNormalizer();

    // ── the ministry's page 1 for "Acetaminophen", in the order it really returns them ──────────
    private static final Row COLD_SYRUP =
            row("100000001", "판콜에이내복액", true,
                    "Acetaminophen", "Anhydrous Caffeine", "Chlorpheniramine Maleate",
                    "Guaifenesin", "Pentoxyverine Citrate", "Phenylephrine Hydrochloride");
    private static final Row EXPORT_ONLY =
            row("100000002", "유니온아세트아미노펜정500밀리그람(수출용)", true, "Acetaminophen");
    private static final Row EXPORT_NAMED =
            row("100000003", "게보린정(수출명:돌로린정)", true, "Acetaminophen", "Anhydrous Caffeine", "Isopropylantipyrine");
    private static final Row PRESCRIPTION_TABLET =
            row("100000004", "전문아세트아미노펜정", false, "Acetaminophen");
    private static final Row PLAIN_TABLET =
            row("100000005", "삼남아세트아미노펜정", true, "Acetaminophen");
    /**
     * Single-ingredient and alphabetically first, so it ranks ahead of {@link #PLAIN_TABLET} and the
     * walk has to step over it. Modelled on 파라게직정, which really is licensed and really has no
     * e약은요 entry.
     */
    private static final Row NO_GUIDANCE =
            row("100000006", "가나아세트아미노펜정", true, "Acetaminophen");
    private static final Row IBUPROFEN_TABLET =
            row("100000007", "부루펜정400밀리그램(이부프로펜)", true, "Ibuprofen");

    // The three the live API actually returned, all sorting ahead of 삼남… by name.
    private static final Row NARPEN =
            row("198701721", "나르펜정400밀리그램(이부프로펜)", true, "Ibuprofen");
    private static final Row NAPROXEN_TABLET =
            row("199401319", "나로펜정(나프록센나트륨)", true, "Naproxen Sodium");
    private static final Row NAPROXIN =
            row("200204388", "나프록신정(나프록센나트륨)", true, "Naproxen Sodium");

    @Nested
    @DisplayName("what reaches the model")
    class Selection {

        @Test
        @DisplayName("a single-ingredient OTC tablet outranks the cold syrup the API returns first")
        void ranksBySimplicityNotByApiOrder() {
            RetrievedContext ctx = retrieve(
                    world().ingredient("Acetaminophen", COLD_SYRUP, PLAIN_TABLET),
                    query("Acetaminophen"));

            assertThat(names(ctx)).containsExactly("삼남아세트아미노펜정", "판콜에이내복액");
        }

        @Test
        @DisplayName("over-the-counter outranks prescription — a traveller cannot buy 전문의약품")
        void otcFirst() {
            RetrievedContext ctx = retrieve(
                    world().ingredient("Acetaminophen", PRESCRIPTION_TABLET, PLAIN_TABLET),
                    query("Acetaminophen"));

            assertThat(names(ctx)).containsExactly("삼남아세트아미노펜정", "전문아세트아미노펜정");
        }

        @Test
        @DisplayName("(수출용) is dropped; (수출명) is not — 게보린 is sold in Korean pharmacies")
        void exportOnlyIsDroppedButExportNamedIsNot() {
            RetrievedContext ctx = retrieve(
                    world().ingredient("Acetaminophen", EXPORT_ONLY, EXPORT_NAMED),
                    query("Acetaminophen"));

            assertThat(names(ctx)).containsExactly("게보린정(수출명:돌로린정)");
        }

        @Test
        @DisplayName("a product with no e약은요 entry is skipped, and the walk continues past it")
        void noGuidanceMeansNoContext() {
            World world = world().ingredient("Acetaminophen", NO_GUIDANCE, PLAIN_TABLET);
            world.withoutGuidance(NO_GUIDANCE.itemSeq());

            // 가나… ranks first. Nothing else would stop it reaching the model.
            RetrievedContext ctx = retrieve(world, query("Acetaminophen"));

            assertThat(names(ctx)).containsExactly("삼남아세트아미노펜정");
        }

        @Test
        @DisplayName("the top-ranked product is skipped for lack of guidance, not silently described")
        void noGuidanceAtAllMeansEmptyContext() {
            World world = world().ingredient("Acetaminophen", NO_GUIDANCE);
            world.withoutGuidance(NO_GUIDANCE.itemSeq());

            assertThat(retrieve(world, query("Acetaminophen")).isEmpty()).isTrue();
        }

        @Test
        @DisplayName("at most three drugs, however many match")
        void cappedAtThree() {
            RetrievedContext ctx = retrieve(
                    world().ingredient("Acetaminophen",
                            PLAIN_TABLET, COLD_SYRUP, EXPORT_NAMED, PRESCRIPTION_TABLET),
                    query("Acetaminophen"));

            assertThat(ctx.drugs()).hasSize(3);
        }

        @Test
        @DisplayName("a product the user named by hand outranks anything we inferred")
        void namedBeatsInferred() {
            World world = world()
                    .name("부루펜", IBUPROFEN_TABLET)
                    .ingredient("Acetaminophen", PLAIN_TABLET);

            RetrievedContext ctx = retrieve(
                    world, new RetrievalQuery(List.of("Acetaminophen"), List.of("부루펜")));

            assertThat(names(ctx)).containsExactly("부루펜정400밀리그램(이부프로펜)", "삼남아세트아미노펜정");
        }

        @Test
        @DisplayName("each proposed ingredient is represented, in the order it was proposed")
        void oneDrugPerIngredientInOrder() {
            World world = world()
                    .ingredient("Acetaminophen", PLAIN_TABLET)
                    .ingredient("Ibuprofen", IBUPROFEN_TABLET)
                    .ingredient("Naproxen", NAPROXEN_TABLET);

            RetrievedContext ctx = retrieve(
                    world, new RetrievalQuery(List.of("Acetaminophen", "Ibuprofen", "Naproxen"), List.of()));

            assertThat(names(ctx))
                    .containsExactly("삼남아세트아미노펜정", "부루펜정400밀리그램(이부프로펜)", "나로펜정(나프록센나트륨)");
        }

        @Test
        @DisplayName("the alphabet does not overrule the ingredient the model put first")
        void alphabeticalTieBreakDoesNotCrossIngredients() {
            // The live regression, pinned. Every hit is a single-ingredient OTC tablet, so the only
            // remaining tie-break is the product name — and 나…, 나…, 나… all sort ahead of 삼남….
            // Pooled into one sort this returned three NSAIDs and zero acetaminophen.
            World world = world()
                    .ingredient("Acetaminophen", PLAIN_TABLET)
                    .ingredient("Ibuprofen", NARPEN)
                    .ingredient("Naproxen", NAPROXEN_TABLET, NAPROXIN);

            RetrievedContext ctx = retrieve(
                    world, new RetrievalQuery(List.of("Acetaminophen", "Ibuprofen", "Naproxen"), List.of()));

            assertThat(names(ctx)).first().isEqualTo("삼남아세트아미노펜정");
            assertThat(names(ctx)).doesNotContain("나프록신정(나프록센나트륨)");
        }

        @Test
        @DisplayName("an ingredient with no hits does not consume a slot")
        void emptyIngredientIsSkipped() {
            World world = world()
                    .ingredient("Acetaminophen")
                    .ingredient("Ibuprofen", IBUPROFEN_TABLET, NARPEN);

            RetrievedContext ctx = retrieve(
                    world, new RetrievalQuery(List.of("Acetaminophen", "Ibuprofen"), List.of()));

            assertThat(names(ctx)).containsExactly("나르펜정400밀리그램(이부프로펜)", "부루펜정400밀리그램(이부프로펜)");
        }

        @Test
        @DisplayName("the same product found twice appears once")
        void deduplicatesByItemSeq() {
            World world = world()
                    .name("삼남", PLAIN_TABLET)
                    .ingredient("Acetaminophen", PLAIN_TABLET);

            RetrievedContext ctx = retrieve(
                    world, new RetrievalQuery(List.of("Acetaminophen"), List.of("삼남")));

            assertThat(ctx.drugs()).hasSize(1);
        }

        @Test
        @DisplayName("no search terms, no upstream calls, no context")
        void emptyQueryRetrievesNothing() {
            World world = world();
            RetrievedContext ctx = retrieve(world, RetrievalQuery.EMPTY);

            assertThat(ctx.isEmpty()).isTrue();
            assertThat(world.calls.get()).isZero();
        }
    }

    @Nested
    @DisplayName("the allergy gate, applied to retrieval itself")
    class Allergy {

        @Test
        @DisplayName("a medicine the user is allergic to is never offered for their symptom")
        void blockedIngredientHitsAreDropped() {
            RetrievedContext ctx = retrieve(
                    world().ingredient("Ibuprofen", IBUPROFEN_TABLET),
                    query("Ibuprofen"),
                    avoiding("Ibuprofen"));

            assertThat(ctx.isEmpty()).isTrue();
        }

        @Test
        @DisplayName("but a medicine they asked about by name is shown, marked blocked")
        void namedBlockedProductsAreKept() {
            RetrievedContext ctx = retrieve(
                    world().name("부루펜", IBUPROFEN_TABLET),
                    new RetrievalQuery(List.of(), List.of("부루펜")),
                    avoiding("Ibuprofen"));

            assertThat(ctx.drugs()).hasSize(1);
            assertThat(ctx.drugs().get(0).allergyCheck().status()).isEqualTo(AllergyCheck.Status.BLOCKED);
        }

        @Test
        @DisplayName("MAIN_INGR_ENG can reveal an allergen ITEM_INGR_NAME hid — then it is dropped")
        void detailRechecksTheVerdict() {
            // The list operation reports no allergen. The detail operation, which reads a different
            // field, reports one. Nothing else in the pipeline would catch this.
            World world = world().ingredient("Acetaminophen", PLAIN_TABLET);
            world.detailIngredients(PLAIN_TABLET.itemSeq(), List.of("Ibuprofen"));

            RetrievedContext ctx = retrieve(world, query("Acetaminophen"), avoiding("Ibuprofen"));

            assertThat(ctx.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("what the answer validator is handed")
    class Provenance {

        @Test
        @DisplayName("allowedProductNames is exactly the set of drugs in the context")
        void allowedNamesMatchTheContext() {
            RetrievedContext ctx = retrieve(
                    world().ingredient("Acetaminophen", PLAIN_TABLET, COLD_SYRUP),
                    query("Acetaminophen"));

            assertThat(ctx.allowedProductNames())
                    .isEqualTo(ctx.drugs().stream().map(Drug::nameKo).collect(Collectors.toSet()));
        }

        @Test
        @DisplayName("one source per drug, and every drug's sourceRefId points into it")
        void sourcesLineUpWithDrugs() {
            RetrievedContext ctx = retrieve(
                    world().ingredient("Acetaminophen", PLAIN_TABLET, COLD_SYRUP),
                    query("Acetaminophen"));

            assertThat(ctx.sources()).hasSameSizeAs(ctx.drugs());
            assertThat(ctx.drugs()).allSatisfy(d ->
                    assertThat(ctx.sources()).anyMatch(s -> s.id().equals(d.source().id())));
        }

        @Test
        @DisplayName("an empty retrieval carries no names and no sources")
        void emptyIsEmpty() {
            assertThat(RetrievedContext.EMPTY.allowedProductNames()).isEmpty();
            assertThat(RetrievedContext.EMPTY.sources()).isEmpty();
            assertThat(RetrievedContext.EMPTY.isEmpty()).isTrue();
        }
    }

    @Nested
    @DisplayName("수출용 vs 수출명")
    class ExportNaming {

        @Test
        @DisplayName("only 수출용 means the product cannot be bought here")
        void distinguishesTheTwo() {
            assertThat(DrugService.isExportOnly("유니온아세트아미노펜정500밀리그람(수출용)")).isTrue();
            assertThat(DrugService.isExportOnly("코푸레스정(수출용)(수출명:안티그립정)")).isTrue();
            assertThat(DrugService.isExportOnly("게보린정(수출명:돌로린정)")).isFalse();
            assertThat(DrugService.isExportOnly("삼남아세트아미노펜정")).isFalse();
            assertThat(DrugService.isExportOnly(null)).isFalse();
        }
    }

    // ── the world the stubs describe ────────────────────────────────────────────────────────────

    private record Row(String itemSeq, String nameKo, boolean otc, List<String> ingredientsEn) {}

    private static Row row(String itemSeq, String nameKo, boolean otc, String... ingredients) {
        return new Row(itemSeq, nameKo, otc, List.of(ingredients));
    }

    /** A hand-built 식약처: what each search returns, which products have guidance, what detail says. */
    private static final class World {
        final Map<String, List<Row>> byName = new LinkedHashMap<>();
        final Map<String, List<Row>> byIngredient = new LinkedHashMap<>();
        final Map<String, List<String>> detailIngredients = new LinkedHashMap<>();
        final Set<String> withoutGuidance = new java.util.HashSet<>();
        /** Retrieval fans out across threads now, so a plain int would lose increments. */
        final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        World name(String query, Row... rows) {
            byName.put(query, Arrays.asList(rows));
            return this;
        }

        World ingredient(String term, Row... rows) {
            byIngredient.put(term, Arrays.asList(rows));
            return this;
        }

        void withoutGuidance(String itemSeq) {
            withoutGuidance.add(itemSeq);
        }

        void detailIngredients(String itemSeq, List<String> ingredients) {
            detailIngredients.put(itemSeq, ingredients);
        }

        Row find(String itemSeq) {
            return java.util.stream.Stream.concat(
                            byName.values().stream().flatMap(List::stream),
                            byIngredient.values().stream().flatMap(List::stream))
                    .filter(r -> r.itemSeq().equals(itemSeq))
                    .findFirst()
                    .orElse(null);
        }
    }

    private World world() {
        return new World();
    }

    private static RetrievalQuery query(String ingredient) {
        return new RetrievalQuery(List.of(ingredient), List.of());
    }

    private Set<String> avoiding(String... raw) {
        return Arrays.stream(raw)
                .map(r -> normalizer.normalize(r).key())
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
    }

    private static List<String> names(RetrievedContext ctx) {
        return ctx.drugs().stream().map(Drug::nameKo).toList();
    }

    private RetrievedContext retrieve(World world, RetrievalQuery query) {
        return retrieve(world, query, Set.of());
    }

    private RetrievedContext retrieve(World world, RetrievalQuery query, Set<String> avoided) {
        return service(world).retrieve(query, avoided);
    }

    private DrugService service(World world) {
        var props = new PublicApiProperties(
                "", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x", "https://x");
        var mode = new DataModeProperties(DataModeProperties.DataMode.FIXTURE);
        var loader = new FixtureLoader(new ObjectMapper());

        var permission = new DrugPermissionApiClient(null, props, mode, loader) {
            @Override
            public List<Permitted> findByName(String itemName) {
                world.calls.incrementAndGet();
                return permitted(world.byName.getOrDefault(itemName, List.of()));
            }

            @Override
            public List<Permitted> findByIngredient(String ingredientEn) {
                world.calls.incrementAndGet();
                return permitted(world.byIngredient.getOrDefault(ingredientEn, List.of()));
            }

            @Override
            public Optional<PermittedDetail> detail(String itemSeq) {
                world.calls.incrementAndGet();
                Row r = world.find(itemSeq);
                if (r == null) {
                    return Optional.empty();
                }
                List<String> ingredients = world.detailIngredients.getOrDefault(itemSeq, r.ingredientsEn());
                return Optional.of(new PermittedDetail(
                        r.itemSeq(), r.nameKo(), "제조사", ingredients, "성분",
                        r.otc() ? PrescriptionStatus.OTC : PrescriptionStatus.PRESCRIPTION));
            }

            private List<Permitted> permitted(List<Row> rows) {
                List<Permitted> out = new ArrayList<>();
                for (Row r : rows) {
                    out.add(new Permitted(
                            r.itemSeq(), r.nameKo(), null, "제조사", r.ingredientsEn(),
                            r.otc() ? PrescriptionStatus.OTC : PrescriptionStatus.PRESCRIPTION,
                            true));
                }
                return out;
            }
        };

        var easyDrug = new EasyDrugApiClient(null, props, mode, loader) {
            @Override
            public Optional<Narrated> findBySeq(String itemSeq) {
                world.calls.incrementAndGet();
                if (world.withoutGuidance.contains(itemSeq)) {
                    return Optional.empty();
                }
                Row r = world.find(itemSeq);
                return r == null
                        ? Optional.empty()
                        : Optional.of(new Narrated(itemSeq, r.nameKo(), "제조사",
                                new Drug.Narrative("해열, 진통", "1일 3회", null, null, null, null, null)));
            }
        };

        var dur = new DurApiClient(null, props, mode, loader) {
            @Override
            public List<DurWarning> warningsFor(String itemSeq) {
                world.calls.incrementAndGet();
                return List.of();
            }
        };

        return new DrugService(
                permission, easyDrug, dur, new AllergyChecker(normalizer), normalizer, mode, CLOCK);
    }
}
