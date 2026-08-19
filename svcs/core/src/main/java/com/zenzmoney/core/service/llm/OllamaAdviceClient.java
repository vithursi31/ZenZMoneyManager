package com.zenzmoney.core.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.zenzmoney.core.logging.AppLog;
import com.zenzmoney.core.service.insight.SpendingSnapshot;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers a money question through the same self-hosted Qwen2.5 the extraction path
 * uses (F-1.16).
 *
 * <p>Two differences from {@link OllamaExtractionClient}, both deliberate:
 * <ul>
 *   <li><b>No {@code format:"json"}.</b> The output is a sentence for a person, not
 *       a structure for the backend.</li>
 *   <li><b>A higher temperature.</b> Extraction wants the same answer twice;
 *       advice at 0.1 reads like a form letter. The figures are fixed by the prompt
 *       either way, so the freedom is only in the wording.</li>
 * </ul>
 *
 * <p>It never throws, for the same reason the extraction client doesn't: a model
 * that is down should cost the user an apology, not a 5xx (§9).
 */
@Service
public class OllamaAdviceClient implements LlmAdviceClient {

    /** Routed to llm.log — this path costs the most compute of anything in the app. */
    private static final Logger log = AppLog.LLM;

    private static final String CHAT_PATH = "/api/chat";

    /** Six sentences of advice fits well inside this; it caps a runaway generation. */
    private static final int MAX_OUTPUT_TOKENS = 400;

    /** A question is a sentence. The cap bounds prompt size and therefore model time. */
    static final int MAX_QUESTION_CHARS = 500;

    private final WebClient webClient;
    private final AdvicePrompt prompt;
    private final String model;
    private final double temperature;

    public OllamaAdviceClient(@Qualifier("llmWebClient") WebClient webClient,
                              AdvicePrompt prompt,
                              @Value("${zenzmoney.llm.model}") String model,
                              @Value("${zenzmoney.llm.advice-temperature}") double temperature) {
        this.webClient = webClient;
        this.prompt = prompt;
        this.model = model;
        this.temperature = temperature;
    }

    @Override
    public String answer(String question, SpendingSnapshot snapshot) {
        if (question == null || question.isBlank() || snapshot == null) {
            return null;
        }
        long startedAt = System.currentTimeMillis();
        try {
            JsonNode response = webClient.post()
                    .uri(CHAT_PATH)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(buildRequestBody(question, snapshot))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            String answer = readAnswer(response);
            // Duration matters more here than anywhere: a slow model and a broken one look
            // identical from outside, and this call is the slowest thing a user waits on.
            log.info("LLM advice answered in {}ms (model={}, chars={})",
                    System.currentTimeMillis() - startedAt, model,
                    answer == null ? 0 : answer.length());
            return answer;
        } catch (Exception e) {
            log.warn("LLM advice call failed after {}ms (model={}): {}",
                    System.currentTimeMillis() - startedAt, model, e.toString());
            return null;
        }
    }

    Map<String, Object> buildRequestBody(String question, SpendingSnapshot snapshot) {
        String bounded = question.length() > MAX_QUESTION_CHARS
                ? question.substring(0, MAX_QUESTION_CHARS)
                : question;

        Map<String, Object> options = new LinkedHashMap<>();
        options.put("temperature", temperature);
        options.put("num_predict", MAX_OUTPUT_TOKENS);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("stream", false);
        body.put("options", options);
        body.put("messages", List.of(
                Map.of("role", "system", "content", prompt.system(snapshot)),
                Map.of("role", "user", "content", bounded)));
        return body;
    }

    /** Ollama returns the reply text in {@code message.content}. */
    private String readAnswer(JsonNode response) {
        String content = response == null ? "" : response.path("message").path("content").asText("");
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            log.warn("LLM advice returned an empty reply (model={})", model);
            return null;
        }
        return trimmed;
    }
}
