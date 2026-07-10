package com.mermaid.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

/**
 * The JSON Schema we hand the provider so it cannot answer in the wrong shape (DEV-102).
 *
 * <p><b>This is not the validator.</b> Two different artifacts, deliberately:
 *
 * <ul>
 *   <li>This schema constrains <i>generation</i>. It lives or dies by what a given provider's strict
 *       mode happens to support, and it can only describe shape.
 *   <li>{@link AnswerValidator} constrains <i>truth</i>. It checks the cross-field invariants a schema
 *       cannot express — that a named medicine was actually retrieved, that an emergency offers the
 *       call. It runs on every answer whether or not the schema was enforced, and it is the source of
 *       truth.
 * </ul>
 *
 * <p>A model with perfect schema compliance can still name a medicine that does not exist. Removing
 * the validator because the schema is enforced would be exactly the wrong lesson.
 *
 * <p>Measured against the live endpoint on 2026-07-10: {@code glm-5.2} accepts this document whole —
 * {@code $defs}, {@code $ref}, {@code const}, enums, nullable type unions — under {@code strict: true}
 * and answers with the camelCase keys {@code MermAidAnswer} expects. {@code deepseek-v4-flash} answers
 * {@code 400} to the same request. Hence {@link com.mermaid.config.LlmProperties#supportsStructuredOutput}.
 *
 * <p>Keep the field names in sync with {@link com.mermaid.chat.dto.MermAidAnswer} and with {@link
 * SystemPromptProvider}. When the record changed and the prompt did not, a live model dutifully
 * returned {@code reply} while the server read {@code summary}, and every answer rendered blank.
 */
@Slf4j
@Component
public class AnswerSchemaProvider {

    private static final String PATH = "schemas/mermaid-answer.provider.schema.json";

    /** The name the provider echoes back. Purely cosmetic; it appears in their logs, not ours. */
    public static final String SCHEMA_NAME = "mermaid_answer";

    private final JsonNode schema;

    AnswerSchemaProvider(ObjectMapper objectMapper) {
        try (InputStream in = new ClassPathResource(PATH).getInputStream()) {
            this.schema = objectMapper.readTree(in);
        } catch (IOException e) {
            // Failing to start is right. A silently absent schema would look like a working server
            // that quietly stopped constraining the model.
            throw new IllegalStateException("Could not read " + PATH, e);
        }
    }

    public JsonNode get() {
        return schema;
    }
}
