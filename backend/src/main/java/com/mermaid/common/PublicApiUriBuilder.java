package com.mermaid.common;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builds data.go.kr request URIs without double-encoding the service key.
 *
 * <p><b>The trap.</b> data.go.kr hands you two versions of the same key: an "Encoding" key that is
 * already percent-encoded (it contains {@code %2B}, {@code %2F}, {@code %3D}) and a "Decoding" key
 * holding the raw {@code +}, {@code /}, {@code =}. If you feed the Encoding key through {@code
 * UriComponentsBuilder}, it escapes the {@code %} itself — {@code %2B} becomes {@code %252B} — and
 * the API answers {@code SERVICE_KEY_IS_NOT_REGISTERED_ERROR}. Everyone hits this once.
 *
 * <p>We take the <b>Decoding</b> key, encode every parameter exactly once here, and hand {@link
 * java.net.URI} a string it will not touch again.
 *
 * <p>Note the JSON parameter name differs per API: the pharmacy and HIRA services want {@code
 * _type=json}; the MFDS drug services want {@code type=json}. Callers pass whichever applies.
 */
public final class PublicApiUriBuilder {

    private final String baseUrl;
    private final String operation;
    private final Map<String, String> params = new LinkedHashMap<>();

    private PublicApiUriBuilder(String baseUrl, String operation) {
        this.baseUrl = baseUrl;
        this.operation = operation;
    }

    public static PublicApiUriBuilder of(String baseUrl, String operation) {
        return new PublicApiUriBuilder(baseUrl, operation);
    }

    /** The raw (Decoding) service key. Encoded once, here. */
    public PublicApiUriBuilder serviceKey(String decodedKey) {
        return param("serviceKey", decodedKey);
    }

    public PublicApiUriBuilder param(String name, Object value) {
        if (value != null) {
            params.put(name, String.valueOf(value));
        }
        return this;
    }

    public URI build() {
        StringBuilder sb = new StringBuilder(baseUrl);
        if (!baseUrl.endsWith("/")) {
            sb.append('/');
        }
        sb.append(operation);

        char separator = '?';
        for (Map.Entry<String, String> e : params.entrySet()) {
            sb.append(separator)
                    .append(encode(e.getKey()))
                    .append('=')
                    .append(encode(e.getValue()));
            separator = '&';
        }
        // URI.create() parses without re-encoding. Do NOT route this through
        // UriComponentsBuilder — that is exactly what breaks the service key.
        return URI.create(sb.toString());
    }

    private static String encode(String raw) {
        // URLEncoder does form-encoding. It already turns a literal '+' into %2B — which is what
        // the service key needs — but it also renders a SPACE as '+'. Any '+' left in its output
        // therefore came from a space, so that is what we rewrite. Rewriting '+' to %2B here
        // instead would turn every space into a plus sign.
        return URLEncoder.encode(raw, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
