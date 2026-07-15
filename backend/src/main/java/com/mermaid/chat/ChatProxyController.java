package com.mermaid.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.mermaid.chat.DrugContextRetriever.DrugContext;
import com.mermaid.chat.AnswerValidator.ViolationCode;
import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.chat.dto.UiAction;
import com.mermaid.common.SourceRef;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * OpenAI-compatible chat endpoint (FR-01, TC-01).
 *
 * <p>The frontend points the official {@code openai} JS SDK at this path via {@code baseURL} and
 * passes a dummy key. No custom parsing layer on either side.
 *
 * <p>Deliberately <i>not</i> the Vercel AI SDK protocol: {@code useChat} validates each SSE line
 * against its own {@code UIMessageChunk} schema and rejects OpenAI's {@code choices[].delta} shape.
 * See spec §2-3.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/chat")
@RequiredArgsConstructor
public class ChatProxyController {

    private static final long STREAM_TIMEOUT_MS = 120_000L;
    /**
     * Names the allergens it is talking about, and only those.
     *
     * <p>"The named allergens were checked by name only" reads, in a session that also carries
     * picker selections, as though it covers those too — so a card the server stamped {@code
     * blocked} for a reviewed ingredient sits next to a sentence saying that match was merely a
     * name and needs confirming. The caveat belongs to the words the user typed, so it says them.
     */
    static String unverifiedAllergenCaveat(Set<String> typedAllergens) {
        return "The allergens you typed ("
                + String.join(", ", new TreeSet<>(typedAllergens))
                + ") were checked against ingredient names only; a pharmacist must confirm them. "
                + "This does not change any other warning on this page.";
    }

    private final ChatProxyService chatProxyService;
    private final DrugContextRetriever drugContextRetriever;
    private final StructuredOutputFallback fallback;
    private final AnswerValidator answerValidator;
    private final EmergencyTriage emergencyTriage;
    private final com.mermaid.drug.IngredientNormalizer ingredientNormalizer;
    private final ObjectMapper objectMapper;

    /**
     * One path, two content types, chosen by the request body rather than by {@code Accept}.
     *
     * <p>Before either branch, rule-based screening runs (SA-03). If it fires we answer from code and
     * never reach the model — because a live model, asked about crushing chest pain, replied {@code
     * urgency: "unknown"}. Someone having a heart attack does not get a second try.
     *
     * <p>The blocking branch returns a {@link ResponseEntity} with an explicit JSON content type. It
     * has to: with {@code produces} listing {@code text/event-stream} first and a bare {@code Object}
     * return, a client that sends no {@code Accept} header makes Spring try to write the JSON
     * envelope as an SSE stream, and the request dies with {@code
     * HttpMessageNotWritableException: No converter for ObjectNode}.
     */
    @PostMapping(
            path = "/completions",
            produces = {MediaType.TEXT_EVENT_STREAM_VALUE, MediaType.APPLICATION_JSON_VALUE})
    public Object completions(@RequestBody JsonNode request) {
        boolean stream = ChatProxyService.wantsStream(request);

        var redFlag = emergencyTriage.screen(ChatProxyService.userMessagesForSafety(request));
        if (redFlag.isPresent()) {
            log.warn("Emergency triage fired: {} — answering without calling the model", redFlag.get());
            return respond(withUnverifiedAllergenCaveat(
                    emergencyTriage.emergencyAnswer(redFlag.get()),
                    MermaidRequestExtension.excludedIngredients(request).unverifiedTerms()), stream);
        }

        return respond(answer(request), stream);
    }

    /**
     * Both content types carry the same fully-validated answer.
     *
     * <p>{@code stream=true} used to relay the upstream token by token, and the post-processing
     * invariants could not run on it: the JSON is only parseable once the last chunk lands, by which
     * time the earlier ones are already on the wire. A streamed drug recommendation therefore reached
     * the browser unverified. It now arrives as one SSE chunk instead — the {@code openai} SDK reads
     * it identically, and no answer leaves this server unchecked. Progressive rendering is a feature;
     * an unvalidated medicine is a defect.
     */
    private Object respond(MermAidAnswer answer, boolean stream) {
        return stream
                ? streamOne(answer)
                : ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(envelope(answer));
    }

    /** Emits a complete answer as a single OpenAI-shaped SSE chunk, then {@code [DONE]}. */
    private SseEmitter streamOne(MermAidAnswer answer) {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        try {
            ObjectNode delta = objectMapper.createObjectNode();
            delta.put("role", "assistant");
            delta.put("content", objectMapper.writeValueAsString(answer));

            ObjectNode choice = objectMapper.createObjectNode();
            choice.put("index", 0);
            choice.set("delta", delta);
            choice.putNull("finish_reason");

            ObjectNode chunk = objectMapper.createObjectNode();
            chunk.put("object", "chat.completion.chunk");
            chunk.set("choices", objectMapper.createArrayNode().add(choice));

            emitter.send(SseEmitter.event().data(objectMapper.writeValueAsString(chunk)));
            emitter.send(SseEmitter.event().data(ChatProxyService.DONE_SENTINEL));
            emitter.complete();
        } catch (Exception e) {
            emitter.completeWithError(e);
        }
        return emitter;
    }

    /**
     * The two-pass RAG flow (spec §2-2).
     *
     * <pre>
     *   pass 1  DrugContextRetriever  → the only medicines that exist, as far as this turn is concerned
     *   pass 2  the model             → summarises them, and nothing else
     *           StructuredOutputFallback → makes the content parseable
     *           ground()                 → replaces the model's provenance with the server's
     *           AnswerValidator          → makes it true
     * </pre>
     *
     * <p>Pass 1 is what gives invariant 6 its teeth. Before it existed, {@code retrievedProductNames}
     * was empty and every answer naming a medicine was refused — correct for a system that had
     * retrieved nothing, and useless.
     */
    private MermAidAnswer answer(JsonNode request) {
        MermaidRequestExtension.StructuredExclusions exclusions =
                MermaidRequestExtension.excludedIngredients(request);
        return withUnverifiedAllergenCaveat(
                answer(request, exclusions), exclusions.unverifiedTerms());
    }

    private MermAidAnswer answer(
            JsonNode request, MermaidRequestExtension.StructuredExclusions exclusions) {
        String userText = ChatProxyService.lastUserMessage(request);
        DrugContext context =
                drugContextRetriever.retrieve(
                        userText,
                        ChatProxyService.userMessagesForSafety(request),
                        exclusions);
        if (context.directAnswer().isPresent()) {
            return context.directAnswer().orElseThrow();
        }

        JsonNode upstream;
        long startedAt = System.nanoTime();
        try {
            upstream = chatProxyService.complete(request, List.of(context.systemMessage())).block();
        } catch (RuntimeException e) {
            // A slow or unreachable provider is our problem, not the user's. Reactor wraps the
            // timeout, so this is where it lands: without the catch, a model that thinks for too long
            // reports INTERNAL_ERROR and tells a sick person the server is broken.
            log.error("Upstream chat call failed after {}ms", elapsedMs(startedAt), e);
            return unreachable();
        }
        // Pass 2 is the slowest thing this server does, and its cost scales with how much the model
        // has to write — three grounded drug cards is several thousand characters. Log it, or the
        // next person to hit the timeout will start by suspecting the public APIs.
        log.info("RAG pass 2: model answered in {}ms ({} chars of context)",
                elapsedMs(startedAt), context.systemMessage().length());
        if (upstream == null) {
            return unreachable();
        }

        JsonNode messageNode = upstream.path("choices").path(0).path("message");
        if (messageNode.isMissingNode()) {
            log.warn("Upstream returned no choices; shape={}", upstream.fieldNames());
            return unreachable();
        }

        MermAidAnswer parsed = fallback.coerce(messageNode.path("content").asText(null));
        if ("local-fallback".equals(parsed.answerId())) {
            // A malformed response is untrusted model prose, not a structured answer. Returning it as
            // summary text would bypass every drug-card and medical-field invariant below.
            return fallback.safeAnswer(
                    "I could not verify that answer against official data, so I will not show it. "
                            + "Please describe your symptoms again, or visit a pharmacy.");
        }
        if (parsed.urgency() != null
                && parsed.urgency().level() == MermAidAnswer.Urgency.Level.EMERGENCY) {
            // The model's urgency is only an escalation signal. Replacing the whole answer keeps
            // model-authored prose, medicines and UI actions out of a safety state. This must happen
            // before grounding: an incomplete model card is untrusted and may not even have a key.
            return emergencyTriage.emergencyAnswer("MODEL_ESCALATION");
        }
        MermAidAnswer coerced = ground(parsed, context);

        List<ViolationCode> violations = answerValidator.validate(coerced, context.groundedDrugs());
        if (!violations.isEmpty()) {
            log.warn("answer_validation_failed total={} codes={}",
                    violations.size(), violationCounts(violations));
            return fallback.safeAnswer(
                    "I could not verify that answer against official data, so I will not show it. "
                            + "Please describe your symptoms again, or visit a pharmacy.");
        }
        // Two independent things keep this from laundering a hallucination, and EITHER alone is enough:
        //
        //   1. it runs after the validator, so a card invariant 6 rejected never reaches it, and
        //   2. the substitution is key-preserving — a name is only ever replaced by the ministry's name
        //      for the SAME normalized key, so a fabricated ingredient keeps the model's own text and
        //      invariant 6 still sees it.
        //
        // Measured, not assumed: break either one and the suite stays green, because the other covers
        // it. Break BOTH — stamp before validation AND substitute positionally — and a card naming a
        // drug we never retrieved gets relabelled into a valid one, and the test that pins this goes
        // red. An earlier comment here claimed the ordering was "the whole design"; that was a claim
        // the code did not support, and the mutation that proved it took thirty seconds. Keep both.
        return withMinistryDisplayNames(coerced, context);
    }

    /**
     * The name on the card is the ministry's name. Invariant 8, for the two fields left.
     *
     * <p>{@code productNameEn} was never validated by anything. A card could name the retrieved Korean
     * 타이레놀 with the right source and the right ingredients — and print <b>"Advil"</b> as its English
     * name, under a footer citing 식약처. And an ingredient's {@code nameEn} survived only a NORMALIZED
     * comparison, so an alias or a salt form ("Ibuprofen Lysine" where the ministry said "Ibuprofen")
     * passed invariant 6 and was shown as the ministry's word for it.
     *
     * <p>We hold both — {@code Drug.nameEn} and {@code Drug.ingredientsEn} — so neither is the model's
     * to write.
     *
     * <p><b>The substitution is key-preserving, and that is the invariant.</b> An ingredient name is
     * replaced only by the ministry's name for the SAME normalized key; a name the record does not hold
     * is left exactly as the model wrote it, so invariant 6 still sees it and still rejects the card.
     * This must never become a positional substitution — mapping the ministry's first ingredient onto
     * the model's first row would let a card naming the wrong drug be quietly relabelled into a valid
     * one, and the hallucination gate would be rescuing hallucinations. A test pins this.
     */
    private MermAidAnswer withMinistryDisplayNames(MermAidAnswer answer, DrugContext context) {
        List<MermAidAnswer.DrugCard> stamped = new ArrayList<>(answer.drugs().size());
        for (MermAidAnswer.DrugCard drug : answer.drugs()) {
            DrugContextRetriever.GroundedDrug source =
                    context.groundedDrugs().get(drug.productNameKo());
            if (source == null) {
                stamped.add(drug);
                continue;
            }
            stamped.add(new MermAidAnswer.DrugCard(
                    drug.id(),
                    drug.productNameKo(),
                    source.productNameEn(),
                    ministryIngredientNames(drug.ingredients(), source),
                    drug.indicationSummary(),
                    drug.directionsSummary(),
                    drug.labelCautions(),
                    drug.warnings(),
                    drug.prescriptionStatus(),
                    drug.allergyCheck(),
                    drug.sourceRefId()));
        }
        return new MermAidAnswer(
                answer.schemaVersion(),
                answer.answerId(),
                answer.language(),
                answer.dataStatus(),
                answer.urgency(),
                answer.summary(),
                answer.clarifyingQuestions(),
                answer.guidance(),
                List.copyOf(stamped),
                answer.uiActions(),
                answer.sourceRefs(),
                answer.warnings(),
                answer.disclaimer());
    }

    private List<MermAidAnswer.Ingredient> ministryIngredientNames(
            List<MermAidAnswer.Ingredient> ingredients, DrugContextRetriever.GroundedDrug source) {
        Map<String, String> byKey = new HashMap<>();
        for (String name : source.ingredientNamesEn()) {
            byKey.putIfAbsent(ingredientNormalizer.normalizeIdentity(name).key(), name);
        }
        List<MermAidAnswer.Ingredient> named = new ArrayList<>(ingredients.size());
        for (MermAidAnswer.Ingredient i : ingredients) {
            String key =
                    i.nameEn() == null
                            ? null
                            : ingredientNormalizer.normalizeIdentity(i.nameEn()).key();
            String ministry = key == null ? null : byKey.get(key);
            named.add(new MermAidAnswer.Ingredient(
                    null, ministry != null ? ministry : i.nameEn(), i.normalizedKey(), null, null));
        }
        return List.copyOf(named);
    }

    private static MermAidAnswer withUnverifiedAllergenCaveat(
            MermAidAnswer answer, Set<String> unverifiedAllergens) {
        if (unverifiedAllergens.isEmpty()) {
            return answer;
        }
        List<String> warnings = new ArrayList<>();
        if (answer.warnings() != null) {
            warnings.addAll(answer.warnings());
        }
        String caveat = unverifiedAllergenCaveat(unverifiedAllergens);
        if (!warnings.contains(caveat)) {
            warnings.add(caveat);
        }
        return new MermAidAnswer(
                answer.schemaVersion(),
                answer.answerId(),
                answer.language(),
                answer.dataStatus(),
                answer.urgency(),
                answer.summary(),
                answer.clarifyingQuestions(),
                answer.guidance(),
                answer.drugs(),
                answer.uiActions(),
                answer.sourceRefs(),
                List.copyOf(warnings),
                answer.disclaimer());
    }

    private static Map<ViolationCode, Integer> violationCounts(List<ViolationCode> violations) {
        Map<ViolationCode, Integer> counts = new EnumMap<>(ViolationCode.class);
        violations.forEach(code -> counts.merge(code, 1, Integer::sum));
        return counts;
    }

    /**
     * Replaces the model's {@code sourceRefs} and {@code dataStatus} with the server's own.
     *
     * <p>Provenance is not the model's to author. It has no way of knowing whether a record came from
     * the live ministry API or from a fixture we replayed because the network was down, and a
     * hand-copied {@code retrievedAt} is a timestamp nobody checked. The server retrieved the data, so
     * the server says where it came from.
     *
     * <p>This narrows invariants 1 and 5 to what they should have been all along — <i>does the model's
     * citation point at a source we actually hold?</i> — and turns invariant 3 into a regression guard
     * over our own labelling rather than a gate on the model.
     *
     * <p>The allergy verdict is grounded the same way, and for a sharper reason. The model is handed
     * each product's {@link AllergyCheck} and asked to carry it onto the card. Nothing stopped it from
     * carrying it wrongly: a card that writes {@code no_match_found} over the server's {@code blocked}
     * keeps the same product, the same ingredients and the same source, so every other invariant still
     * passes and the person is shown "no match found" for a drug we blocked. The check is ours to
     * compute and ours to state.
     */
    private MermAidAnswer ground(MermAidAnswer answer, DrugContext context) {
        return new MermAidAnswer(
                answer.schemaVersion(),
                answer.answerId(),
                answer.language(),
                dataStatusOf(context.sources()),
                answer.urgency(),
                answer.summary(),
                answer.clarifyingQuestions(),
                answer.guidance(),
                groundServerRecord(
                        groundDirections(
                                groundAllergyChecks(answer.drugs(), context.groundedDrugs()),
                                context.groundedDrugs()),
                        context.groundedDrugs()),
                distinct(answer.uiActions()),
                context.sources(),
                answer.warnings(),
                answer.disclaimer());
    }

    /** A run of digits, with the separators a dose is written with (1.5, 1~2, 1-2, 1,000). */
    private static final Pattern NUMBER = Pattern.compile("\\d[\\d.,]*");

    /**
     * The dose is the ministry's, verbatim, or there is no dose. Post-processing invariant 7.
     *
     * <p>The model used to write this field, and nothing checked it: a schema-valid answer could say
     * <em>"take 8 tablets every 2 hours"</em> for a label reading 1회 1~2정, 1일 3~4회 — under a footer
     * naming 식약처 as the source. A government-branded overdose (OUT-03).
     *
     * <p>The first fix checked that every number the model wrote appeared in the ministry's text.
     * Review broke it in one line: 만 12세 이상 … 1회 1~2정 contains "12", so <em>"Take 12 tablets once
     * daily"</em> passed — the digit was there, as an AGE. Numbers have roles, and a substring does
     * not carry one. Any check that tries to recover the role has to parse Korean dosage prose, and
     * a parser that is wrong about a dose is the same defect wearing a lab coat.
     *
     * <p>So we stopped trying to check the model and removed its authority instead. <b>The server
     * writes this field.</b> It carries 식약처's own 용법용량, exactly as retrieved, and the model's
     * version is discarded unread. Nothing to verify, because nothing was authored. The card labels
     * it as the official Korean text and says we do not translate doses — the pharmacist reads it
     * with the person, which is where an exact dose was always going to come from.
     *
     * <p>The model keeps what a person only READS. Explaining what a medicine is for, in English, is
     * the job we gave it, and a wrong word there is not an overdose. That is the line this and
     * invariant 8 both draw: bind the model to the record on what a person ACTS on.
     */
    private static List<MermAidAnswer.DrugCard> groundDirections(
            List<MermAidAnswer.DrugCard> drugs,
            Map<String, DrugContextRetriever.GroundedDrug> groundedDrugs) {

        List<MermAidAnswer.DrugCard> grounded = new ArrayList<>(drugs.size());
        for (MermAidAnswer.DrugCard drug : drugs) {
            DrugContextRetriever.GroundedDrug source = groundedDrugs.get(drug.productNameKo());
            String official = source == null ? null : source.officialDosageKo();
            grounded.add(
                    withDirections(drug, official == null || official.isBlank() ? null : official));
        }
        return List.copyOf(grounded);
    }

    /**
     * Every number the model wrote appears somewhere in the ministry's text.
     *
     * <p><b>Read this before you trust it, and before you add another rule to it.</b> It scans DIGITS.
     * A model that writes <em>"take eight tablets every two hours"</em> passes it, because there are no
     * digits in that sentence to check — review found exactly that. Adding "eight" and "two" to a word
     * list buys nothing: the next sentence is "a couple of tablets", and the one after that is "twice
     * the usual amount". <b>Every filter of this shape has a next bypass, and a filter that looks like
     * a gate is worse than none, because we stop worrying.</b>
     *
     * <p>It is kept because it does remove the digit form, and removing it would be strictly worse. It
     * is NOT a gate, and this code must not be extended as if it could become one. The problem it
     * gestures at — model prose stating a dose — is not a property of one field: the same model writes
     * `summary` and `guidance` in the chat bubble directly above this card, with no check at all, and
     * a dose there is exactly as dangerous. Hardening the card while leaving the bubble open is
     * theatre.
     *
     * <p>The real fix is the semantic output gate — a check on MEANING, not on characters —
     * specified at {@code docs/specs/006-semantic-output-gate/spec.md} and tracked as OUT-02. Until it
     * exists, what actually holds the line is: the ministry's own dose is on the card, verbatim, in the
     * only field that may carry one (invariant 7); the system prompt forbids stating a dose anywhere
     * else, in digits or in words; and the card says which of its sentences are a summary rather than
     * the ministry's words. The prompt is an instruction, not an invariant. We know.
     *
     * <p><b>A filter, not a proof.</b> It catches an invented quantity; it cannot tell what ROLE a
     * number played — 만 12세 이상 contains "12", so a sentence reusing it as a tablet count passes.
     * That is exactly why dosing (invariant 7) no longer relies on this and is server-written
     * instead. Cautions still do, because unlike a dose they must be translated to be usable at all,
     * and a caution's numbers are rarely the thing a person acts on. The residual risk — a plausible
     * mistranslation carrying no number — is OUT-02, and still open.
     */
    private static boolean numbersAreGrounded(String directions, String official) {
        Matcher numbers = NUMBER.matcher(directions);
        if (official == null || official.isBlank()) {
            // Nothing to check against. Prose with no quantity in it still says nothing a label
            // could contradict, so it survives; the moment it names a number, it has no source.
            return !numbers.find();
        }
        while (numbers.find()) {
            // Trailing separators are punctuation, not part of the quantity: "1일 3회." ends a
            // sentence, and "3." must still match the label's "3".
            String number = numbers.group().replaceAll("[.,]+$", "");
            if (!official.contains(number)) {
                return false;
            }
        }
        return true;
    }

    private static MermAidAnswer.DrugCard withDirections(
            MermAidAnswer.DrugCard drug, String officialDosageKo) {
        return new MermAidAnswer.DrugCard(
                drug.id(),
                drug.productNameKo(),
                drug.productNameEn(),
                drug.ingredients(),
                drug.indicationSummary(),
                officialDosageKo,
                drug.labelCautions(),
                drug.warnings(),
                drug.prescriptionStatus(),
                drug.allergyCheck(),
                drug.sourceRefId());
    }

    /**
     * The card's warnings, prescription status and cautions, taken back from the model. Invariant 8.
     *
     * <p>Three fields, one principle, and it is invariant 7's: <b>bind the model to the server's
     * record on what a person acts on; leave it free on what they only read.</b>
     *
     * <ul>
     *   <li><b>{@code warnings}</b> — 식약처's DUR contraindications. The model was told to copy every
     *       one of them onto the card and nothing checked that it had, so a dropped contraindication
     *       reached the reader and an invented one reached them under a footer naming the ministry.
     *       Neither shows up in any other invariant: the product, the ingredients and the source are
     *       all still right. We hold the record, so we write it, and the model's array is discarded.
     *   <li><b>{@code prescriptionStatus}</b> — a server fact with a wire value already
     *       ({@code SPCLTY_PBLC}/{@code ETC_OTC_CODE}). There was never anything for the model to
     *       add here, only something for it to get wrong.
     *   <li><b>{@code labelCautions}</b> — the model's English summary of 주의사항·경고·상호작용·부작용.
     *       This one <i>is</i> a translation, like the indication and the directions, so it stays the
     *       model's to write — and is checked the way the directions are, by invariant 7's digit
     *       rule: every number in it must be a number the ministry wrote. A caution for a product
     *       whose caution text we do not hold has no source at all, and does not survive.
     * </ul>
     *
     * <p>A stripped caution becomes {@code null} and the card says so in words — it never becomes an
     * absent section. Silence where a caution was reads as "nothing to be careful about", which is
     * the same trap as {@code no_match_found} read as "safe" (§2-2). The same holds for a product
     * with no DUR record at all: an empty warnings array is a statement, and the card states it.
     *
     * <p>A card naming a product we did not retrieve is left untouched, as in {@link
     * #groundAllergyChecks}: invariant 6 rejects the whole answer for it moments later, and dressing
     * it in server facts first would only disguise the violation.
     */
    private static List<MermAidAnswer.DrugCard> groundServerRecord(
            List<MermAidAnswer.DrugCard> drugs,
            Map<String, DrugContextRetriever.GroundedDrug> groundedDrugs) {

        List<MermAidAnswer.DrugCard> grounded = new ArrayList<>(drugs.size());
        for (MermAidAnswer.DrugCard drug : drugs) {
            DrugContextRetriever.GroundedDrug source = groundedDrugs.get(drug.productNameKo());
            if (source == null) {
                grounded.add(drug);
                continue;
            }
            grounded.add(new MermAidAnswer.DrugCard(
                    drug.id(),
                    drug.productNameKo(),
                    drug.productNameEn(),
                    onlyGroundedIngredientFields(drug.ingredients()),
                    groundedIndication(drug, source),
                    drug.directionsSummary(),
                    groundedCautions(drug, source),
                    source.warnings(),
                    source.prescriptionStatus(),
                    drug.allergyCheck(),
                    drug.sourceRefId()));
        }
        return List.copyOf(grounded);
    }

    /**
     * Null — and the card's own words — for a caution we cannot trace to the ministry's text.
     *
     * <p>Stricter than the directions in one place, and the difference is the point. Directions with
     * no quantity in them survive a product we hold no 용법용량 for, because <em>"follow the dosing on
     * the package"</em> is a pointer and contradicts no label. There is no equivalent caution: a
     * sentence in this field is a medical claim whatever numbers it does or does not contain, and
     * <em>"take care if you have liver problems"</em> is exactly as unsourced as a wrong age
     * threshold. No official caution text, no caution.
     */
    /**
     * An ingredient fact we never retrieved is not a fact. Invariant 8, applied to the whole row.
     *
     * <p>We hold ingredient names <b>in English</b>, and nothing else. {@link
     * com.mermaid.drug.domain.Drug} carries {@code ingredientsEn} — parsed from 허가정보's {@code
     * ITEM_INGR_NAME}, which is English — and the context the model is handed carries the same. There
     * is no Korean ingredient name in the record, no {@code amount} and no {@code unit}, anywhere.
     *
     * <p>So three of the five fields on a card's ingredient row had no source at all, and {@link
     * AnswerValidator} compares normalized ENGLISH keys, which means a row could read
     *
     * <pre>  Acetaminophen · 이부프로펜 · 5000 mg  </pre>
     *
     * and pass every invariant: the English name is the retrieved one, so INV6 is satisfied, while the
     * Korean name beside it is a different drug entirely and the strength is ten times the licensed
     * dose — all of it printed under a footer naming 식약처.
     *
     * <p>They are removed. The English name survives; that one IS grounded, and invariant 6 checks it.
     * The Korean the person needs in a pharmacy is the PRODUCT name, which the card already shows and
     * the server already owns (타이레놀정500밀리그람) — it is what they point at on a shelf.
     *
     * <p>Same rule as invariants 7 and 8 everywhere else: where an output cannot be checked cheaply and
     * soundly, take away the authority to produce it. If 허가정보's {@code MATERIAL_NAME} is ever parsed,
     * these become server facts and can come back.
     */
    private static List<MermAidAnswer.Ingredient> onlyGroundedIngredientFields(
            List<MermAidAnswer.Ingredient> ingredients) {
        if (ingredients == null) {
            return List.of();
        }
        // A null ELEMENT, not a null list. The schema-less retry path accepts `ingredients: [null]`
        // as valid JSON, and this mapper runs BEFORE AnswerValidator gets to fail closed — so a
        // dereference here turns a refusable answer into a 500, and the person gets "something went
        // wrong on our side" instead of the server-authored refusal (OUT-07's shape, one field over).
        // Drop them: a null ingredient carries no name, so invariant 6 will reject the card anyway if
        // the rest of it lies, and dropping is the behaviour that keeps the validator in charge.
        return ingredients.stream()
                .filter(Objects::nonNull)
                .map(i -> new MermAidAnswer.Ingredient(null, i.nameEn(), i.normalizedKey(), null, null))
                .toList();
    }

    /**
     * A dose does not stop being a dose because it is written in the "For" box.
     *
     * <p>Invariant 7 took the model's authority over {@code directionsSummary} away and the card now
     * prints 식약처's own 용법용량 there, untranslated. That closed one field. It did not close the
     * FIELD NEXT TO IT: {@code indicationSummary} is model-owned, rendered on the same card under the
     * same government footer, and rendered <em>above</em> the official dose. Nothing stopped a card
     * from saying
     *
     * <pre>  For: Take 8 tablets every 2 hours.  </pre>
     *
     * and the whole dose gate was bypassed by moving the sentence one box up. Review found this; we
     * had locked a door and left the window.
     *
     * <p>So the indication is bound the same way the cautions are: it is a translation of 식약처's
     * 효능효과, and every number in it must be a number that text contains. A dose is made of numbers
     * the efficacy text does not have, so it does not survive. With no official efficacy text held,
     * no number survives at all.
     *
     * <p>The digit rule remains a filter and not a proof (see {@link #numbersAreGrounded}) — a
     * plausible wrong sentence carrying no number is OUT-02 and still open. What it does close is the
     * one thing that is machine-decidable and that a person acts on: a quantity with no source.
     */
    private static String groundedIndication(
            MermAidAnswer.DrugCard drug, DrugContextRetriever.GroundedDrug source) {
        String indication = drug.indicationSummary();
        if (indication == null || indication.isBlank()) {
            return null;
        }
        String official = source.officialEfficacyKo();
        if (official != null && !official.isBlank() && numbersAreGrounded(indication, official)) {
            return indication;
        }
        log.warn(
                "indication_ungrounded product={} hasOfficialEfficacy={}",
                drug.productNameKo(),
                source.officialEfficacyKo() != null);
        return null;
    }

    private static String groundedCautions(
            MermAidAnswer.DrugCard drug, DrugContextRetriever.GroundedDrug source) {
        String cautions = drug.labelCautions();
        if (cautions == null || cautions.isBlank()) {
            return null;
        }
        String official = source.officialCautionKo();
        if (official != null && !official.isBlank() && numbersAreGrounded(cautions, official)) {
            return cautions;
        }
        // The product name is ours — it came from the ministry. The rejected sentence is model
        // output shaped by whatever the user typed, and a log is not a place to put that (§2-5).
        log.warn(
                "cautions_ungrounded product={} hasOfficialCautions={}",
                drug.productNameKo(),
                source.officialCautionKo() != null);
        return null;
    }

    /**
     * Stamps the server's own allergy verdict onto every card it can identify.
     *
     * <p>A card naming a product we did not retrieve is left as it is — invariant 6 rejects the whole
     * answer for it moments later, and rewriting it here would only disguise the violation.
     */
    private static List<MermAidAnswer.DrugCard> groundAllergyChecks(
            List<MermAidAnswer.DrugCard> drugs,
            Map<String, DrugContextRetriever.GroundedDrug> groundedDrugs) {
        if (drugs == null) {
            return List.of();
        }
        List<MermAidAnswer.DrugCard> grounded = new ArrayList<>(drugs.size());
        for (MermAidAnswer.DrugCard drug : drugs) {
            DrugContextRetriever.GroundedDrug source = groundedDrugs.get(drug.productNameKo());
            if (source == null || source.allergyCheck() == null) {
                grounded.add(drug);
                continue;
            }
            grounded.add(new MermAidAnswer.DrugCard(
                    drug.id(),
                    drug.productNameKo(),
                    drug.productNameEn(),
                    drug.ingredients(),
                    drug.indicationSummary(),
                    drug.directionsSummary(),
                    drug.labelCautions(),
                    drug.warnings(),
                    drug.prescriptionStatus(),
                    source.allergyCheck(),
                    drug.sourceRefId()));
        }
        return List.copyOf(grounded);
    }

    /**
     * The same action, asked for twice, is one action.
     *
     * <p>A live answer really did carry {@code OPEN_FACILITY_MAP} three times — once per drug it
     * described — and the UI would have drawn three identical buttons. {@link UiAction} and its
     * payloads are records, so equality is structural and this is exact: two map requests with
     * different radii both survive.
     */
    private static List<UiAction> distinct(List<UiAction> actions) {
        return actions == null ? List.of() : actions.stream().distinct().toList();
    }

    /** No sources means we grounded nothing — {@code unavailable} is the honest word for that. */
    private static MermAidAnswer.DataStatus dataStatusOf(List<SourceRef> sources) {
        if (sources.isEmpty()) {
            return MermAidAnswer.DataStatus.UNAVAILABLE;
        }
        boolean anyFixture = sources.stream().anyMatch(s -> s.dataMode() == SourceRef.DataMode.FIXTURE);
        boolean anyLive = sources.stream().anyMatch(s -> s.dataMode() == SourceRef.DataMode.LIVE);
        if (anyFixture && anyLive) {
            return MermAidAnswer.DataStatus.MIXED;
        }
        return anyFixture ? MermAidAnswer.DataStatus.FIXTURE : MermAidAnswer.DataStatus.LIVE;
    }

    private MermAidAnswer unreachable() {
        return fallback.safeAnswer("Sorry — I could not reach the assistant. Please try again.");
    }

    private static long elapsedMs(long startedAtNanos) {
        return (System.nanoTime() - startedAtNanos) / 1_000_000;
    }

    /**
     * Wraps an answer in the {@code chat.completion} envelope the {@code openai} SDK expects.
     *
     * <p>The catch used to write {@code "{}"} and say nothing. A {@code SourceRef} carries an {@code
     * Instant}, so an ObjectMapper without {@code JavaTimeModule} turns every grounded answer into a
     * blank card — and the only trace was a blank card. If this ever fires, it must be loud.
     */
    private JsonNode envelope(MermAidAnswer answer) {
        ObjectNode message = objectMapper.createObjectNode().put("role", "assistant");
        try {
            message.put("content", objectMapper.writeValueAsString(answer));
        } catch (Exception e) {
            log.error("Could not serialise the answer; the client will render nothing", e);
            message.put("content", "{}");
        }
        ObjectNode choice = objectMapper.createObjectNode();
        choice.put("index", 0);
        choice.set("message", message);
        choice.put("finish_reason", "stop");

        ObjectNode envelope = objectMapper.createObjectNode();
        envelope.put("object", "chat.completion");
        envelope.set("choices", objectMapper.createArrayNode().add(choice));
        return envelope;
    }
}
