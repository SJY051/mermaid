package com.mermaid.chat;

import com.mermaid.chat.dto.AllergyCheck;
import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.chat.dto.UiAction;
import com.mermaid.common.SourceRef;
import java.util.ArrayList;
import java.util.List;
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
 * <p>Invariant #6 — that every product name matches a record we actually fetched — needs the set of
 * names retrieved during pass 1 of the RAG flow, so callers pass it in. The rest are self-contained.
 */
@Component
public class AnswerValidator {

    /** Anything that could smuggle a link or markup into the UI (invariant #7). */
    private static final Pattern FORBIDDEN_MARKUP =
            Pattern.compile("(?i)(https?://|<\\s*script|javascript:|<\\s*iframe|onerror\\s*=)");

    /**
     * @param retrievedProductNames Korean product names fetched from the MFDS APIs this turn.
     *     Empty means we retrieved nothing, so the answer must not name any drug.
     * @return every violation found. Empty list means the answer may be shown.
     */
    public List<String> validate(MermAidAnswer answer, Set<String> retrievedProductNames) {
        List<String> violations = new ArrayList<>();

        Set<String> sourceIds =
                answer.sourceRefs() == null
                        ? Set.of()
                        : answer.sourceRefs().stream().map(SourceRef::id).collect(Collectors.toSet());

        // 1. Every drug cites a source we actually have.
        for (MermAidAnswer.DrugCard drug : nullSafe(answer.drugs())) {
            if (!sourceIds.contains(drug.sourceRefId())) {
                violations.add(
                        "drug '" + drug.productNameKo() + "' cites unknown source_ref_id " + drug.sourceRefId());
            }
        }

        // 2. A BLOCKED verdict must name what it matched, or it is not actionable.
        for (MermAidAnswer.DrugCard drug : nullSafe(answer.drugs())) {
            AllergyCheck check = drug.allergyCheck();
            if (check != null
                    && check.status() == AllergyCheck.Status.BLOCKED
                    && nullSafe(check.matchedIngredients()).isEmpty()) {
                violations.add("drug '" + drug.productNameKo() + "' is blocked but names no ingredient");
            }
        }

        // 3. A "live" answer cannot rest on fixture data.
        if (answer.dataStatus() == MermAidAnswer.DataStatus.LIVE) {
            boolean anyFixture =
                    nullSafe(answer.sourceRefs()).stream()
                            .anyMatch(s -> s.dataMode() == SourceRef.DataMode.FIXTURE);
            if (anyFixture) {
                violations.add("data_status=live but a source is data_mode=fixture");
            }
        }

        // 4. An emergency must always offer the call. This one exists so that a model which
        //    correctly identifies a heart attack cannot forget to say "call 119".
        if (answer.urgency() != null
                && answer.urgency().level() == MermAidAnswer.Urgency.Level.EMERGENCY
                && !hasEmergencyCall(answer)) {
            violations.add("urgency=emergency but no SHOW_EMERGENCY_CALL action");
        }

        // 5. A claim attributed to official data must cite it.
        for (MermAidAnswer.Guidance g : nullSafe(answer.guidance())) {
            if (g.evidence() == MermAidAnswer.Guidance.Evidence.OFFICIAL_DATA
                    && nullSafe(g.sourceRefIds()).isEmpty()) {
                violations.add("guidance '" + g.id() + "' claims official_data with no source");
            }
        }

        // 6. The model may only name drugs we retrieved. This is the hallucination gate.
        for (MermAidAnswer.DrugCard drug : nullSafe(answer.drugs())) {
            if (!retrievedProductNames.contains(drug.productNameKo())) {
                violations.add(
                        "drug '"
                                + drug.productNameKo()
                                + "' was never retrieved from the MFDS API — refusing to show it");
            }
        }

        // 7. No links, no markup, from a model into our UI.
        for (String text : userVisibleText(answer)) {
            if (text != null && FORBIDDEN_MARKUP.matcher(text).find()) {
                violations.add("answer contains a URL or markup: " + truncate(text));
            }
        }

        return violations;
    }

    private boolean hasEmergencyCall(MermAidAnswer answer) {
        return java.util.stream.Stream.concat(
                        nullSafe(answer.uiActions()).stream(),
                        nullSafe(answer.urgency().actions()).stream())
                .anyMatch(a -> a instanceof UiAction.ShowEmergencyCall);
    }

    private List<String> userVisibleText(MermAidAnswer answer) {
        List<String> texts = new ArrayList<>();
        texts.add(answer.summary());
        texts.add(answer.disclaimer());
        texts.addAll(nullSafe(answer.warnings()));
        texts.addAll(nullSafe(answer.clarifyingQuestions()));
        for (MermAidAnswer.Guidance g : nullSafe(answer.guidance())) {
            texts.add(g.body());
        }
        for (MermAidAnswer.DrugCard d : nullSafe(answer.drugs())) {
            texts.add(d.indicationSummary());
            texts.add(d.directionsSummary());
            texts.addAll(nullSafe(d.warnings()));
        }
        return texts;
    }

    private static <T> List<T> nullSafe(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static String truncate(String s) {
        return s.length() <= 80 ? s : s.substring(0, 80) + "…";
    }
}
