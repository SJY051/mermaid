package com.mermaid.drug;

import com.mermaid.profile.domain.MatchConfidence;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Turns what a user typed into something we can compare against {@code MAIN_INGR_ENG} (DEV-305).
 *
 * <p>"Ibuprofen", "ibuprofen 200mg", "Ibuprofen (Advil)" are one ingredient. The government data
 * writes it "Ibuprofen". Comparing raw strings would miss all three.
 *
 * <p><b>The rule that matters</b> (spec §2-12): an exact match or a <i>reviewed</i> synonym may block
 * a medicine. Nothing else may. A partial overlap yields {@link MatchConfidence#PARTIAL}, which the
 * allergy service renders as a warning, and an LLM is never allowed to promote a guess to a synonym.
 * The dictionary is a file a human signs.
 */
@Slf4j
@Component
public class IngredientNormalizer {

    private static final String SYNONYMS = "/ingredients/synonyms.tsv";

    /**
     * Dose and strength: "200mg", "160밀리그램", "5 %".
     *
     * <p>Note the lookahead rather than a trailing {@code \b}. Java's word boundary is defined
     * against {@code [a-zA-Z0-9_]}, so there is no boundary after '램' and {@code 160밀리그램} survived
     * the strip. The lookbehind guards the leading digit the same way for the ASCII case.
     */
    private static final Pattern DOSE =
            Pattern.compile(
                    "(?i)(?<![a-z0-9])\\d+(?:[.,]\\d+)?\\s*(mg|g|ml|mcg|µg|iu|%|밀리그램|그램|밀리리터)(?![a-z])");

    /** Anything parenthesised: brand names, dose forms, Korean glosses. */
    private static final Pattern PARENTHESISED = Pattern.compile("[（(\\[][^）)\\]]*[）)\\]]");

    private static final Pattern MULTI_SPACE = Pattern.compile("\\s{2,}");

    /**
     * Physical-form qualifiers that do not change the allergen.
     *
     * <p>The MFDS writes "Acetaminophen Granules", "Acetaminophen Micronized", "Anhydrous Caffeine".
     * These are the same substance in a different grind or hydration state. Left alone they matched
     * only partially, and a partial match yields a warning rather than a block — so a live product
     * containing micronized acetaminophen came back as merely "possible" for someone with an
     * acetaminophen allergy. That is a false negative in the one direction that can hurt someone.
     *
     * <p><b>Salt forms are not here on purpose.</b> "Chlorpheniramine Maleate" and "Benztropine
     * Mesylate" are the ingredient's real published name; stripping the salt would over-block. Where
     * a salt shares the allergen (ibuprofen lysine) that belongs in the reviewed synonym file, with a
     * reviewer's name against it.
     *
     * <p>TODO(BE-1 + QA, DEV-305): review this list with the synonym dictionary.
     */
    private static final Set<String> FORM_QUALIFIERS =
            Set.of(
                    "granules",
                    "granule",
                    "micronized",
                    "micronised",
                    "powder",
                    "fine",
                    "crystalline",
                    "anhydrous",
                    "hydrate",
                    "monohydrate",
                    "dihydrate",
                    "coated",
                    "uncoated");

    private final Map<String, String> synonyms = new HashMap<>();

    public IngredientNormalizer() {
        loadSynonyms();
    }

    /**
     * @param raw whatever the user typed
     * @return the key to compare on, and how much we trust it
     */
    public NormalizedTerm normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return new NormalizedTerm(raw, null, MatchConfidence.UNKNOWN);
        }

        String key = canonicalize(raw);
        if (key.isEmpty()) {
            return new NormalizedTerm(raw, null, MatchConfidence.UNKNOWN);
        }

        String canonical = synonyms.get(key);
        if (canonical != null) {
            return new NormalizedTerm(raw, canonical, MatchConfidence.SYNONYM);
        }

        // The input already looks like a canonical ingredient name. We cannot prove it is one —
        // that happens when it matches a government record — but the form is right.
        return new NormalizedTerm(raw, key, MatchConfidence.EXACT);
    }

    /**
     * Lower-cased, unaccented, dose-free, parenthesis-free, and stripped of physical-form qualifiers.
     *
     * <p>Order matters: parentheses go before dose, or "Tylenol (500mg)" leaves an empty bracket.
     */
    public String canonicalize(String raw) {
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        s = PARENTHESISED.matcher(s).replaceAll(" ");
        s = DOSE.matcher(s).replaceAll(" ");
        s = s.toLowerCase(java.util.Locale.ROOT);
        s = s.replace('_', ' ').replace('-', ' ').replace('/', ' ');
        s = MULTI_SPACE.matcher(s).replaceAll(" ");
        return stripFormQualifiers(s.trim());
    }

    /**
     * "acetaminophen micronized" → "acetaminophen"; "sodium chloride" is left alone.
     *
     * <p>If every word is a qualifier we keep the original — an ingredient genuinely called
     * "Anhydrous" does not exist, but neither does an empty key help anyone.
     */
    private static String stripFormQualifiers(String lowered) {
        if (lowered.isEmpty()) {
            return lowered;
        }
        String kept =
                java.util.Arrays.stream(lowered.split(" "))
                        .filter(w -> !FORM_QUALIFIERS.contains(w))
                        .collect(java.util.stream.Collectors.joining(" "))
                        .trim();
        return kept.isEmpty() ? lowered : kept;
    }

    /**
     * The form 허가정보's {@code item_ingr_name} parameter expects: "Ibuprofen", "Sodium Chloride".
     *
     * <p>The upstream search is a <b>case-sensitive substring</b> match, which is a nastier thing
     * than a case-sensitive exact match. {@code Ibuprofen} returns 282 products; {@code ibuprofen}
     * returns 142 — every one of them <i>Dex</i>ibuprofen, and not a single actual ibuprofen product.
     * Searching a user's lower-cased allergy text would silently return the wrong medicines.
     */
    public String toSearchTerm(String raw) {
        String canonical = canonicalize(raw);
        if (canonical.isEmpty()) {
            return "";
        }
        String resolved = synonyms.getOrDefault(canonical, canonical);
        StringBuilder out = new StringBuilder(resolved.length());
        for (String word : resolved.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            if (!out.isEmpty()) {
                out.append(' ');
            }
            out.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return out.toString();
    }

    /**
     * Does a drug's ingredient list contain something the user must avoid?
     *
     * @param drugIngredientEn one entry from {@code MAIN_INGR_ENG} or {@code ITEM_INGR_NAME}
     * @param avoidedKey the user's normalised key
     * @return {@code EXACT}/{@code SYNONYM} when the drug must be blocked, {@code PARTIAL} when it
     *     merely warrants a warning, {@code UNKNOWN} when we cannot compare
     */
    public MatchConfidence compare(String drugIngredientEn, String avoidedKey) {
        if (drugIngredientEn == null || avoidedKey == null || avoidedKey.isBlank()) {
            return MatchConfidence.UNKNOWN;
        }
        String drugKey = canonicalize(drugIngredientEn);
        String drugCanonical = synonyms.getOrDefault(drugKey, drugKey);
        String avoidCanonical = synonyms.getOrDefault(avoidedKey, avoidedKey);

        if (drugCanonical.equals(avoidCanonical)) {
            return drugKey.equals(avoidedKey) ? MatchConfidence.EXACT : MatchConfidence.SYNONYM;
        }
        // "Acetaminophen/Caffeine" contains "acetaminophen" as a whole word. A compound product,
        // not an identity — worth a warning, never a block.
        if (containsWord(drugCanonical, avoidCanonical) || containsWord(avoidCanonical, drugCanonical)) {
            return MatchConfidence.PARTIAL;
        }
        return MatchConfidence.UNKNOWN;
    }

    /** Word-boundary containment, so "aspirin" does not match "aspirinoid". */
    private static boolean containsWord(String haystack, String needle) {
        if (needle.isBlank()) {
            return false;
        }
        return Pattern.compile("\\b" + Pattern.quote(needle) + "\\b").matcher(haystack).find();
    }

    private void loadSynonyms() {
        try (InputStream in = IngredientNormalizer.class.getResourceAsStream(SYNONYMS)) {
            if (in == null) {
                log.warn("no synonym dictionary at {} — only exact matches will block", SYNONYMS);
                return;
            }
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                reader.lines()
                        .map(String::trim)
                        .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                        .forEach(this::putRow);
            }
            log.info("loaded {} reviewed ingredient synonyms", synonyms.size());
        } catch (Exception e) {
            // A missing dictionary must not stop the app: it degrades matching, it does not break it.
            log.error("could not read {}", SYNONYMS, e);
        }
    }

    private void putRow(String line) {
        String[] cols = line.split("\t");
        if (cols.length < 2) {
            log.warn("skipping malformed synonym row: {}", line);
            return;
        }
        synonyms.put(canonicalize(cols[0]), canonicalize(cols[1]));
    }

    /**
     * @param raw exactly what the user typed, kept so we can show it back to them
     * @param key {@code null} when normalisation failed
     */
    public record NormalizedTerm(String raw, String key, MatchConfidence confidence) {}
}
