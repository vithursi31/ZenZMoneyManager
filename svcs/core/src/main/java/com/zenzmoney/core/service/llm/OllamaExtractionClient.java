package com.zenzmoney.core.service.llm;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.logging.AppLog;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Talks to a self-hosted Qwen2.5 through Ollama's {@code /api/chat} endpoint
 * (chat entry plan §3, §5.3).
 *
 * <p>Two properties of this class matter more than its wiring:
 * <ul>
 *   <li><b>It never throws.</b> The model is a best-effort dependency; every
 *       failure becomes {@link LlmExtraction#failed()} so a down model costs the
 *       user a re-phrase, not a 5xx (§9).</li>
 *   <li><b>It never interprets.</b> Amounts stay text, dates stay phrases,
 *       categories stay names. Turning those into money, timestamps, and ids is
 *       {@code IntentResolver}'s job, where it is deterministic and testable.</li>
 * </ul>
 */
@Service
public class OllamaExtractionClient implements LlmExtractionClient {

    /** Routed to llm.log — this path costs compute, so its failures are read on their own. */
    private static final Logger log = AppLog.LLM;

    private static final String CHAT_PATH = "/api/chat";

    /** The extraction JSON is ~60 tokens; this caps a runaway generation well above it. */
    private static final int MAX_OUTPUT_TOKENS = 256;

    /** A capture message is a sentence, not an essay — bounds prompt size and model time. */
    static final int MAX_MESSAGE_CHARS = 500;

    private final WebClient webClient;
    private final ObjectMapper objectMapper;
    private final ExtractionPrompt prompt;
    private final String model;
    private final double temperature;

    public OllamaExtractionClient(@Qualifier("llmWebClient") WebClient webClient,
                                  ObjectMapper objectMapper,
                                  ExtractionPrompt prompt,
                                  @Value("${zenzmoney.llm.model}") String model,
                                  @Value("${zenzmoney.llm.temperature}") double temperature) {
        this.webClient = webClient;
        this.objectMapper = objectMapper;
        this.prompt = prompt;
        this.model = model;
        this.temperature = temperature;
    }

    @Override
    public LlmExtraction extract(String message, List<String> categoryNames) {
        if (message == null || message.isBlank()) {
            return LlmExtraction.failed();
        }
        try {
            JsonNode response = webClient.post()
                    .uri(CHAT_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(buildRequestBody(message, categoryNames))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            return readExtraction(response);
        } catch (Exception e) {
            // Timeout, connection refused, 5xx — all the same to the caller (§9).
            log.warn("LLM extraction call failed (model={}): {}", model, e.toString());
            return LlmExtraction.failed();
        }
    }

    /**
     * The Ollama chat request. {@code stream:false} returns the whole reply in one
     * body, and {@code format:"json"} puts the model in JSON mode so the reply is an
     * object rather than a sentence wrapped around one. A low temperature is what
     * makes the same message extract the same way twice.
     */
    Map<String, Object> buildRequestBody(String message, List<String> categoryNames) {
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
        body.put("options", options);
        body.put("messages", List.of(
                Map.of("role", "system", "content", prompt.system(categoryNames)),
                Map.of("role", "user", "content", bounded)));
        return body;
    }

    /** Ollama wraps the model's answer in {@code message.content} as a JSON string. */
    private LlmExtraction readExtraction(JsonNode response) {
        String content = response == null ? "" : response.path("message").path("content").asText("");
        if (content.isBlank()) {
            log.warn("LLM returned an empty reply (model={})", model);
            return LlmExtraction.failed();
        }

        JsonNode json;
        try {
            json = objectMapper.readTree(content);
        } catch (JsonProcessingException e) {
            // The raw output is the only way to diagnose a prompt regression, and it
            // contains the user's own message — DEBUG only, never a production log.
            log.warn("LLM returned unparseable content (model={})", model);
            log.debug("LLM raw content: {}", content);
            return LlmExtraction.failed();
        }
        if (!json.isObject()) {
            log.warn("LLM returned JSON that is not an object (model={})", model);
            return LlmExtraction.failed();
        }

        LlmExtraction extraction = new LlmExtraction();
        extraction.setIntent(toEnum(IntentType.class, text(json, "intent"), IntentType.UNKNOWN));
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
