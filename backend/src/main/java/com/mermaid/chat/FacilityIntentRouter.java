package com.mermaid.chat;

import com.mermaid.chat.dto.MermAidAnswer;
import com.mermaid.chat.dto.UiAction;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Adds a bounded, server-owned map action when the user explicitly asks to find a facility. */
@Component
final class FacilityIntentRouter {

    private static final int DEFAULT_RADIUS_M = 1_000;
    private static final Pattern PHARMACY =
            Pattern.compile("\\b(?:pharmacy|pharmacies|drugstore|drugstores)\\b");
    private static final Pattern HOSPITAL = Pattern.compile("\\bhospitals?\\b");
    private static final Pattern LOCATION_REQUEST = Pattern.compile(
            "\\b(?:closest|directions?|find|locate|map|nearest|nearby|search|show|where)\\b"
                    + "|\\blook\\s+for\\b|\\b(?:near|around)\\s+me\\b");
    private static final Pattern OPEN_FILTER_REQUEST = Pattern.compile(
            "\\b(?:open|opened)\\b(?:\\s+\\w+){0,2}\\s+\\b(?:now|currently|today|tonight)\\b"
                    + "|\\b(?:currently|still)\\s+open\\b"
                    + "|\\b(?:which|what|any|is|are)\\b[^?.!]{0,80}\\bopen\\b"
                    + "|\\b24[ -]?hours?\\b");

    MermAidAnswer route(String userText, MermAidAnswer answer) {
        if (userText == null || userText.isBlank() || answer == null) {
            return answer;
        }

        String normalized = userText.toLowerCase(Locale.ROOT);
        boolean pharmacy = PHARMACY.matcher(normalized).find();
        boolean hospital = HOSPITAL.matcher(normalized).find();
        boolean openNow = OPEN_FILTER_REQUEST.matcher(normalized).find();
        if (pharmacy == hospital
                || (!LOCATION_REQUEST.matcher(normalized).find() && !openNow)) {
            return answer;
        }

        String type = pharmacy ? "pharmacy" : "hospital";
        UiAction.OpenFacilityMap action = new UiAction.OpenFacilityMap(new UiAction.MapPayload(
                List.of(type), openNow, DEFAULT_RADIUS_M));
        List<UiAction> actions = new ArrayList<>();
        if (answer.uiActions() != null) {
            actions.addAll(answer.uiActions());
        }
        if (!actions.contains(action)) {
            actions.add(action);
        }

        return new MermAidAnswer(
                answer.schemaVersion(),
                answer.answerId(),
                answer.language(),
                answer.dataStatus(),
                answer.urgency(),
                terminalSummary(answer, type),
                answer.clarifyingQuestions(),
                answer.guidance(),
                answer.drugs(),
                List.copyOf(actions),
                answer.sourceRefs(),
                answer.warnings(),
                answer.disclaimer());
    }

    private static String terminalSummary(MermAidAnswer answer, String type) {
        String facilities = type.equals("pharmacy") ? "pharmacies" : "hospitals";
        if (ServerAuthoredSearchUnavailableAnswer.ANSWER_ID.equals(answer.answerId())) {
            return "I could not prepare an official medicine lookup for this request, so no medicine "
                    + "recommendation is shown. You can still use the map below to look for nearby "
                    + facilities
                    + ". Ask a licensed pharmacist or doctor about medicine.";
        }
        if (ServerAuthoredEmptyAnswer.ANSWER_ID.equals(answer.answerId())) {
            return "No verified medicine recommendation is shown for this turn. You can use the map "
                    + "below to look for nearby "
                    + facilities
                    + ", and ask a licensed pharmacist or doctor about medicine.";
        }
        return answer.summary();
    }
}
