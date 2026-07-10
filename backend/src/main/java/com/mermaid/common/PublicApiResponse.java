package com.mermaid.common;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Unwraps a data.go.kr response, whichever of the two shapes it arrived in.
 *
 * <p>Verified against live responses on 2026-07-10 (see {@code src/test/resources/fixtures/}):
 *
 * <pre>{@code
 * 식약처   (1471000/…)  →  { "header": {...}, "body": {...} }
 * 약국·심평원 (B5…)      →  { "response": { "header": {...}, "body": {...} } }
 * }</pre>
 *
 * <p>A parser that assumes {@code response.header} silently sees nothing on the MFDS services. This
 * class is the only place that difference is allowed to exist.
 *
 * <p>Two more traps it handles:
 *
 * <ul>
 *   <li><b>A single result is an object, not an array.</b> {@code items.item} is {@code {...}} for
 *       one row and {@code [{...}]} for many. {@link #items()} always gives you a list.
 *   <li><b>A bad service key returns HTTP 200</b> carrying an error envelope. {@link #requireOk()}
 *       throws unless {@code resultCode} is {@code "00"}.
 * </ul>
 */
public final class PublicApiResponse {

    private static final String RESULT_OK = "00";

    private final JsonNode header;
    private final JsonNode body;

    private PublicApiResponse(JsonNode header, JsonNode body) {
        this.header = header;
        this.body = body;
    }

    /** Accepts either envelope shape. */
    public static PublicApiResponse of(JsonNode root) {
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw new PublicApiException("Empty response body");
        }
        JsonNode envelope = root.has("response") ? root.get("response") : root;
        return new PublicApiResponse(envelope.path("header"), envelope.path("body"));
    }

    public String resultCode() {
        return header.path("resultCode").asText("");
    }

    public String resultMsg() {
        return header.path("resultMsg").asText("");
    }

    public boolean isOk() {
        return RESULT_OK.equals(resultCode());
    }

    /**
     * @throws PublicApiException when the service answered 200 with an error envelope — the usual
     *     symptom of a wrong or unregistered service key
     */
    public PublicApiResponse requireOk() {
        if (!isOk()) {
            throw new PublicApiException(
                    "public API returned resultCode=" + resultCode() + " (" + resultMsg() + ")");
        }
        return this;
    }

    public int totalCount() {
        return body.path("totalCount").asInt(0);
    }

    /**
     * The rows, always as a list.
     *
     * <p>Handles {@code body.items.item} (약국·심평원), {@code body.items} as an array (식약처), and the
     * one-result-is-an-object case in both.
     */
    public List<JsonNode> items() {
        JsonNode items = body.path("items");
        if (items.isMissingNode() || items.isNull()) {
            return List.of();
        }
        JsonNode rows = items.has("item") ? items.get("item") : items;

        List<JsonNode> out = new ArrayList<>();
        if (rows.isArray()) {
            for (Iterator<JsonNode> it = rows.elements(); it.hasNext(); ) {
                out.add(it.next());
            }
        } else if (rows.isObject()) {
            out.add(rows);
        }
        return out;
    }

    /**
     * Reads a field as text regardless of whether the service sent a string or a number.
     *
     * <p>Not defensive coding — the pharmacy service really does this inside one object:
     *
     * <pre>{@code
     * "dutyTime1s": "0900",   // string
     * "dutyTime1c": 1900      // number
     * }</pre>
     *
     * @return {@code null} when the field is absent or JSON null, never the string {@code "null"}
     */
    public static String text(JsonNode row, String field) {
        JsonNode v = row.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asText();
    }

    /** @return {@code null} when absent, so callers can tell "no value" from "zero" */
    public static Double number(JsonNode row, String field) {
        JsonNode v = row.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        if (v.isNumber()) {
            return v.doubleValue();
        }
        try {
            return Double.parseDouble(v.asText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
