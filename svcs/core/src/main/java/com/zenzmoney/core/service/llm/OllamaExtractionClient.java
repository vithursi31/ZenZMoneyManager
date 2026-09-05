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
 * Talks to a self-hosted Qwen2.5 through Ollama's {@code /api/chat} endpoint
 * (F-1.11, §5.3).
 *
 * <p>Two properties of this class matter more than its wiring:
 * <ul>
 *   <li><b>It never throws.</b> The model is a best-effort dependency; every
 *       failure becomes {@link LlmExtractionBatch#failed()} so a down model costs
 *       the user a re-phrase, not a 5xx (§9).</li>
 *   <li><b>It never interprets.</b> Amounts stay text, dates stay phrases,
 *       categories stay names. Turning those into money, timestamps, and ids is
 *       {@code IntentResolver}'s job, where it is deterministic and testable.</li>
 * </ul>
 */
@Service
@ConditionalOnProperty(name = "zenzmoney.llm.provider", havingValue = "ollama", matchIfMissing = true)
public class OllamaExtractionClient implements LlmExtractionClient {

    /** Routed to llm.log — this path costs compute, so its failures are read on their own. */
    private static final Logger log = AppLog.LLM;

    private static final String CHAT_PATH = "/api/chat";

    /**
     * One item is ~70 tokens and a message can name several. Sized for a realistic
     * worst case (six items) rather than the common one, because a cap that truncates
     * mid-array turns a good extraction into an unparseable one.
     */
    private static final int MAX_OUTPUT_TOKENS = 768;

    /**
     * A sentence naming more distinct amounts than this is not a capture message.
     * Bounds the fan-out so one message cannot write an unbounded number of rows.
     */
    static final int MAX_ITEMS = 8;

    /** A capture message is a sentence, not an essay — bounds prompt size and model time. */
    static final int MAX_MESSAGE_CHARS = 500;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final ExtractionPrompt prompt;
    private final String model;
    private final double temperature;
    private final String keepAlive;
    private final boolean logPayloads;

    public OllamaExtractionClient(@Qualifier("llmWebClient") WebClient webClient,
                                  ObjectMapper objectMapper,
                                  ExtractionPrompt prompt,
                                  @Value("${zenzmoney.llm.model}") String model,
                                  @Value("${zenzmoney.llm.temperature}") double temperature,
                                  @Value("${zenzmoney.llm.keep-alive}") String keepAlive,
                                  @Value("${zenzmoney.llm.log-payloads:false}") boolean logPayloads) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.prompt = prompt;
        this.model = model;
        this.temperature = temperature;
        this.keepAlive = keepAlive;
        this.logPayloads = logPayloads;
    }

    @Override
    public LlmExtractionBatch extract(String message, List<String> categoryNames, String conversation) {
        if (message == null || message.isBlank()) {
            return LlmExtractionBatch.failed();
        }
        Map<String, Object> body = buildRequestBody(message, categoryNames, conversation);
        try {
            JsonNode response = webClient.post()
                    .uri(CHAT_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            logRoundTrip(body, response);
            return readBatch(response);
        } catch (Exception e) {
            // Timeout, connection refused, 5xx — all the same to the caller (§9).
            log.warn("LLM extraction call failed (model={}): {}", model, e.toString());
            return LlmExtractionBatch.failed();
        }
    }

    /**
     * The Ollama chat request. {@code stream:false} returns the whole reply in one
     * body, and {@code format:"json"} puts the model in JSON mode so the reply is an
     * object rather than a sentence wrapped around one. A low temperature is what
     * makes the same message extract the same way twice.
     */
    Map<String, Object> buildRequestBody(String message, List<String> categoryNames, String conversation) {
        String bounded = message.length() > MAX_MESSAGE_CHARS
                ? message.substring(0, MAX_MESSAGE_CHARS)
                : message;

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", temperature);
        options.put("num_predict", MAX_OUTPUT_TOKENS);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("format", "json");
        // Qwen3 and its siblings reason aloud before answering. We want a JSON object, not
        // a monologue: the reasoning is tokens we pay for at ~4/s on CPU and then discard.
        // Harmless on a model with no thinking mode, so it is sent unconditionally rather
        // than made a per-model switch.
        body.put("think", false);
        // Ollama evicts an idle model after 5 minutes by default, and reloading this one
        // costs ~27s on top of a 4s generation — so without this the *first message after
        // any short pause* times out and the user is told "I couldn't read that". Holding
        // it resident trades ~1GB of RAM for that never happening.
        body.put("keep_alive", keepAlive);
        body.put("options", options);
        body.put("messages", List.of(
                Map.of("role", "system", "content", prompt.system(categoryNames, conversation)),
                Map.of("role", "user", "content", bounded)));
        return body;
    }

    /**
     * Writes the whole round trip — the system prompt, the user's message and the raw
     * reply — into {@code llm.log}, so a prompt can be debugged against what the model
     * actually saw rather than what it was meant to see.
     *
     * <p><b>Off unless {@code zenzmoney.llm.log-payloads} is true, which only
     * {@code application-loc.properties} sets</b> — the same rule the OTP dev-fallback in
     * {@code SmtpEmailSender} follows. The payload carries the user's own words and every
     * category name they own, and {@code llm.log} is kept 30 days; everywhere else these
     * logs stay shape-only ({@code chars=41}), which is what makes them safe to keep.
     */
    private void logRoundTrip(Map<String, Object> body, JsonNode response) {
        if (!logPayloads) {
            return;
        }
        @SuppressWarnings("unchecked")
        List<Map<String, String>> messages = (List<Map<String, String>>) body.get("messages");
        log.debug("[DEV] >>> system prompt to {}:\n{}", model, messages.get(0).get("content"));
        log.debug("[DEV] >>> user message:\n{}", messages.get(1).get("content"));
        log.debug("[DEV] <<< raw reply:\n{}",
                response == null ? "(none)" : response.path("message").path("content").asText(""));
    }

    /** Ollama wraps the model's answer in {@code message.content} as a JSON string. */
    private LlmExtractionBatch readBatch(JsonNode response) {
        String content = response == null ? "" : response.path("message").path("content").asText("");
        if (content.isBlank()) {
            log.warn("LLM returned an empty reply (model={})", model);
            return LlmExtractionBatch.failed();
        }

        JsonNode json;
        try {
            json = objectMapper.readTree(content);
        } catch (JsonProcessingException e) {
            // The raw output is the only way to diagnose a prompt regression, and it
            // contains the user's own message — DEBUG only, never a production log.
            log.warn("LLM returned unparseable content (model={})", model);
            log.debug("LLM raw content: {}", content);
            return LlmExtractionBatch.failed();
        }
        if (!json.isObject()) {
            log.warn("LLM returned JSON that is not an object (model={})", model);
            return LlmExtractionBatch.failed();
        }

        IntentType intent = toEnum(IntentType.class, text(json, "intent"), IntentType.UNKNOWN);
        JsonNode items = json.path("items");

        // A model that ignored the array contract and answered with the fields inline is
        // still telling us something usable; reading it as a one-item batch costs nothing
        // and is the difference between a capture and "I couldn't read that".
        if (!items.isArray()) {
            if (json.has("amount") || json.has("txnType") || json.has("categoryGuess")) {
                log.debug("LLM answered a bare object rather than an items array (model={})", model);
                return LlmExtractionBatch.of(intent, List.of(readItem(json, intent)));
            }
            return LlmExtractionBatch.of(intent, List.of());
        }

        List<LlmExtraction> extracted = new ArrayList<>();
        for (JsonNode item : items) {
            if (!item.isObject()) {
                continue;
            }
            if (extracted.size() == MAX_ITEMS) {
                log.warn("LLM returned more than {} items; the rest are ignored (model={})",
                        MAX_ITEMS, model);
                break;
            }
            extracted.add(readItem(item, intent));
        }
        return LlmExtractionBatch.of(intent, extracted);
    }

    /**
     * One money event. {@code kind} decides recurring-ness rather than the message
     * intent, so a message mixing a one-off and a subscription reads correctly.
     */
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
     * A field's trimmed text, or null when it is absent, JSON null, blank, or the
     * literal string "null" — which instruct models emit often enough to matter.
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

    /** Models occasionally answer 95 for "95%"; the confidence branch expects 0–1. */
    private static double clamp(double confidence) {
        return Math.max(0d, Math.min(1d, confidence));
    }
}
