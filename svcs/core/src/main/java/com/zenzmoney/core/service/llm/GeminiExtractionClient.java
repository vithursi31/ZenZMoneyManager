package com.zenzmoney.core.service.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.domain.RecurringCadence;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.logging.AppLog;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a capture message through Google's hosted model, used when
 * {@code zenzmoney.llm.provider=gemini}.
 *
 * <p><b>Same contract, different transport.</b> It sends the same
 * {@code extraction-system.md} and returns the same {@link LlmExtractionBatch}, so
 * nothing downstream — {@code IntentResolver}, every backend guard, {@code ChatService} —
 * can tell which model answered. That is what the {@link LlmExtractionClient} seam is
 * for.
 *
 * <p><b>The one real upgrade over Ollama is {@code responseSchema}.</b> Ollama's
 * {@code format:"json"} only promises <em>valid</em> JSON; a schema promises
 * <em>our</em> JSON — the fields, their types, and that {@code items} is an array. The
 * malformed-shape failures that needed defending against in the prompt are refused by
 * the API instead.
 *
 * <p>Like its Ollama counterpart it <b>never throws</b>: an unreachable or unreadable
 * model becomes {@link LlmExtractionBatch#failed()} so chat degrades to a reply rather
 * than a 5xx (§9).
 */
@Service
@ConditionalOnProperty(name = "zenzmoney.llm.provider", havingValue = "gemini")
public class GeminiExtractionClient implements LlmExtractionClient {

    private static final Logger log = AppLog.LLM;

    /** A capture message is a sentence, not an essay — bounds prompt size and cost. */
    static final int MAX_MESSAGE_CHARS = 500;

    /**
     * A sentence naming more distinct amounts than this is not a capture message.
     * Bounds the fan-out so one message cannot write an unbounded number of rows.
     */
    static final int MAX_ITEMS = 8;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final ExtractionPrompt prompt;
    private final String model;
    private final double temperature;
    private final boolean logPayloads;

    public GeminiExtractionClient(@Qualifier("geminiWebClient") WebClient webClient,
                                  ObjectMapper objectMapper,
                                  ExtractionPrompt prompt,
                                  @Value("${zenzmoney.gemini.model}") String model,
                                  @Value("${zenzmoney.gemini.temperature}") double temperature,
                                  @Value("${zenzmoney.llm.log-payloads:false}") boolean logPayloads) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.prompt = prompt;
        this.model = model;
        this.temperature = temperature;
        this.logPayloads = logPayloads;
    }

    @Override
    public LlmExtractionBatch extract(String message, List<String> categoryNames, String conversation) {
        if (message == null || message.isBlank()) {
            return LlmExtractionBatch.failed();
        }
        Map<String, Object> body = buildRequestBody(message, categoryNames, conversation);
        long startedAt = System.currentTimeMillis();
        try {
            JsonNode response = webClient.post()
                    .uri("/v1beta/models/{model}:generateContent", model)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            logRoundTrip(body, response);
            logUsage(response);
            LlmExtractionBatch batch = readBatch(response);
            log.info("Gemini extraction in {}ms (model={}, items={}, failed={})",
                    System.currentTimeMillis() - startedAt, model, batch.getItems().size(),
                    batch.isFailed());
            return batch;
        } catch (Exception e) {
            // Timeout, 401 on a bad key, 429 on quota — all the same to the caller (§9).
            // The message carries no key: it is a header, and WebClient does not echo it.
            log.warn("Gemini extraction call failed after {}ms (model={}): {}",
                    System.currentTimeMillis() - startedAt, model, e.toString());
            return LlmExtractionBatch.failed();
        }
    }

    /**
     * The generateContent request. The prompt goes in {@code systemInstruction} rather
     * than as a first user turn so the API treats it as standing instruction — and so it
     * is the part a future context-cache would pin.
     */
    Map<String, Object> buildRequestBody(String message, List<String> categoryNames, String conversation) {
        String bounded = message.length() > MAX_MESSAGE_CHARS
                ? message.substring(0, MAX_MESSAGE_CHARS)
                : message;

        Map<String, Object> generationConfig = new LinkedHashMap<>();
        generationConfig.put("temperature", temperature);
        generationConfig.put("responseMimeType", "application/json");
        generationConfig.put("responseSchema", responseSchema());

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction",
                Map.of("parts", List.of(Map.of("text", prompt.system(categoryNames, conversation)))));
        body.put("contents",
                List.of(Map.of("role", "user", "parts", List.of(Map.of("text", bounded)))));
        body.put("generationConfig", generationConfig);
        return body;
    }

    /**
     * The exact shape {@code readBatch} parses, declared to the API so it cannot come
     * back otherwise. Enum members are listed rather than left as free strings: an
     * invented {@code txnType} is then refused at the source instead of silently
     * becoming null in {@code toEnum}.
     */
    private static Map<String, Object> responseSchema() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("type", "OBJECT");
        item.put("properties", new LinkedHashMap<>(Map.of(
                "kind", enumOf("TRANSACTION", "RECURRING"),
                "txnType", nullableEnumOf(TransactionType.class),
                "amount", Map.of("type", "STRING", "nullable", true),
                "cadence", nullableEnumOf(RecurringCadence.class),
                "categoryGuess", Map.of("type", "STRING", "nullable", true),
                "dateExpr", Map.of("type", "STRING", "nullable", true),
                "payee", Map.of("type", "STRING", "nullable", true),
                "note", Map.of("type", "STRING", "nullable", true),
                "confidence", Map.of("type", "NUMBER"))));
        item.put("required", List.of("kind", "confidence"));

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "OBJECT");
        schema.put("properties", new LinkedHashMap<>(Map.of(
                "intent", enumOf(names((Enum<?>[]) IntentType.values())),
                "items", Map.of("type", "ARRAY", "items", item))));
        schema.put("required", List.of("intent", "items"));
        return schema;
    }

    private static Map<String, Object> enumOf(String... values) {
        return Map.of("type", "STRING", "enum", List.of(values));
    }

    private static <E extends Enum<E>> Map<String, Object> nullableEnumOf(Class<E> type) {
        return Map.of("type", "STRING", "nullable", true, "enum", List.of(names(type.getEnumConstants())));
    }

    private static String[] names(Enum<?>[] values) {
        String[] out = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = values[i].name();
        }
        return out;
    }

    /** Gemini returns the model's text at {@code candidates[0].content.parts[0].text}. */
    private LlmExtractionBatch readBatch(JsonNode response) {
        String content = response == null ? "" : response
                .path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        if (content.isBlank()) {
            // A blocked prompt or an empty candidate list lands here. The reason is worth
            // having, and carries no user text.
            log.warn("Gemini returned no usable candidate (model={}, finishReason={})", model,
                    response == null ? "none"
                            : response.path("candidates").path(0).path("finishReason").asText("unknown"));
            return LlmExtractionBatch.failed();
        }

        JsonNode json;
        try {
            json = objectMapper.readTree(content);
        } catch (JsonProcessingException e) {
            // The raw output is the only way to diagnose a schema regression, and it
            // contains the user's own message — DEBUG only, never a production log.
            log.warn("Gemini returned unparseable content (model={})", model);
            log.debug("Gemini raw content: {}", content);
            return LlmExtractionBatch.failed();
        }
        if (!json.isObject()) {
            log.warn("Gemini returned JSON that is not an object (model={})", model);
            return LlmExtractionBatch.failed();
        }

        IntentType intent = toEnum(IntentType.class, text(json, "intent"), IntentType.UNKNOWN);
        JsonNode items = json.path("items");

        // The schema makes this the exceptional path rather than the expected one, but a
        // bare object is still usable and is the difference between a capture and a shrug.
        if (!items.isArray()) {
            if (json.has("amount") || json.has("txnType") || json.has("categoryGuess")) {
                log.debug("Gemini answered a bare object rather than an items array (model={})", model);
                return LlmExtractionBatch.of(intent, List.of(readItem(json, intent)));
            }
            return LlmExtractionBatch.of(intent, List.of());
        }

        List<LlmExtraction> extracted = new ArrayList<>();
        for (JsonNode node : items) {
            if (!node.isObject()) {
                continue;
            }
            if (extracted.size() == MAX_ITEMS) {
                log.warn("Gemini returned more than {} items; the rest are ignored (model={})",
                        MAX_ITEMS, model);
                break;
            }
            extracted.add(readItem(node, intent));
        }
        return LlmExtractionBatch.of(intent, extracted);
    }

    /** Identical field reading to the Ollama client — the contract is the prompt's, not the API's. */
    private static LlmExtraction readItem(JsonNode json, IntentType intent) {
        boolean recurring = "RECURRING".equalsIgnoreCase(text(json, "kind"))
                || intent == IntentType.CREATE_RECURRING;

        LlmExtraction extraction = new LlmExtraction();
        extraction.setIntent(recurring && intent == IntentType.CREATE_TRANSACTION
                ? IntentType.CREATE_RECURRING
                : intent);
        extraction.setRecurring(recurring);
        extraction.setCadence(toEnum(RecurringCadence.class, text(json, "cadence"), null));
        extraction.setTxnType(toEnum(TransactionType.class, text(json, "txnType"), null));
        extraction.setAmountRaw(text(json, "amount"));
        extraction.setCategoryGuess(text(json, "categoryGuess"));
        extraction.setDateExpr(text(json, "dateExpr"));
        extraction.setPayee(text(json, "payee"));
        extraction.setNote(text(json, "note"));
        extraction.setConfidence(clamp(json.path("confidence").asDouble(0d)));
        return extraction;
    }

    /**
     * Writes the whole round trip into {@code llm.log}. Off unless
     * {@code zenzmoney.llm.log-payloads} is true, which only {@code application-loc}
     * sets — and under this provider the payload has <em>already left the building</em>,
     * so the log is a second copy of something now held by a third party.
     *
     * <p>The nested request maps are unwrapped to their text rather than dumped: a
     * {@code Map.toString()} of the body prints {@code {parts=[{text=...}]}} around a
     * 17KB prompt, which is unreadable exactly when it is being read to debug something.
     */
    private void logRoundTrip(Map<String, Object> body, JsonNode response) {
        if (!logPayloads) {
            return;
        }
        log.debug("[DEV] >>> system prompt to {}:\n{}", model, systemTextOf(body));
        log.debug("[DEV] >>> user message:\n{}", userTextOf(body));
        log.debug("[DEV] <<< reply text:\n{}", response == null ? "(none)" : replyTextOf(response));
    }

    /**
     * What the call cost, from Gemini's own counters. At INFO because it is the number a
     * bill is made of and carries no user content — the shape-not-content rule the rest
     * of these logs follow.
     */
    private void logUsage(JsonNode response) {
        if (response == null) {
            return;
        }
        JsonNode usage = response.path("usageMetadata");
        if (usage.isMissingNode()) {
            return;
        }
        log.info("Gemini tokens: prompt={} output={} total={} (model={})",
                usage.path("promptTokenCount").asInt(-1),
                usage.path("candidatesTokenCount").asInt(-1),
                usage.path("totalTokenCount").asInt(-1), model);
    }

    @SuppressWarnings("unchecked")
    private static String systemTextOf(Map<String, Object> body) {
        Map<String, Object> system = (Map<String, Object>) body.get("systemInstruction");
        List<Map<String, String>> parts = (List<Map<String, String>>) system.get("parts");
        return parts.get(0).get("text");
    }

    @SuppressWarnings("unchecked")
    private static String userTextOf(Map<String, Object> body) {
        List<Map<String, Object>> contents = (List<Map<String, Object>>) body.get("contents");
        List<Map<String, String>> parts = (List<Map<String, String>>) contents.get(0).get("parts");
        return parts.get(0).get("text");
    }

    private static String replyTextOf(JsonNode response) {
        return response.path("candidates").path(0).path("content")
                .path("parts").path(0).path("text").asText("(no text part)");
    }

    /**
     * A field's trimmed text, or null when it is absent, JSON null, blank, or the
     * literal string "null".
     */
    private static String text(JsonNode json, String field) {
        JsonNode node = json.path(field);
        if (node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText("").trim();
        return value.isEmpty() || "null".equalsIgnoreCase(value) ? null : value;
    }

    /** Lenient by design: an unrecognised label is a bad guess, not a broken request. */
    private static <E extends Enum<E>> E toEnum(Class<E> type, String raw, E fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private static double clamp(double confidence) {
        return Math.max(0d, Math.min(1d, confidence));
    }
}
