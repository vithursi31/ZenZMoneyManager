package com.zenzmoney.core.service.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.zenzmoney.core.logging.AppLog;
import com.zenzmoney.core.service.insight.SpendingSnapshot;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Answers a money question through Google's hosted model (F-1.16), used when
 * {@code zenzmoney.llm.provider=gemini}.
 *
 * <p>Two differences from {@link GeminiExtractionClient}, the same two that separate
 * the Ollama pair: <b>no response schema</b>, because the output is a sentence for a
 * person rather than a structure for the backend; and a <b>higher temperature</b>,
 * because advice at 0.1 reads like a form letter. The figures are fixed by the prompt
 * either way — the freedom is only in the wording.
 *
 * <p>Never throws: a model that is down costs the user an apology, not a 5xx (§9).
 */
@Service
@ConditionalOnProperty(name = "zenzmoney.llm.provider", havingValue = "gemini")
public class GeminiAdviceClient implements LlmAdviceClient {

    private static final Logger log = AppLog.LLM;

    /** A question is a sentence. The cap bounds prompt size and therefore cost. */
    static final int MAX_QUESTION_CHARS = 500;

    private final WebClient webClient;
    private final AdvicePrompt prompt;
    private final String model;
    private final double temperature;
    private final boolean logPayloads;

    public GeminiAdviceClient(@Qualifier("geminiWebClient") WebClient webClient,
                              AdvicePrompt prompt,
                              @Value("${zenzmoney.gemini.advice-model}") String model,
                              @Value("${zenzmoney.gemini.advice-temperature}") double temperature,
                              @Value("${zenzmoney.llm.log-payloads:false}") boolean logPayloads) {
        this.webClient = webClient;
        this.prompt = prompt;
        this.model = model;
        this.temperature = temperature;
        this.logPayloads = logPayloads;
    }

    @Override
    public String answer(String question, SpendingSnapshot snapshot) {
        if (question == null || question.isBlank() || snapshot == null) {
            return null;
        }
        long startedAt = System.currentTimeMillis();
        try {
            JsonNode response = webClient.post()
                    .uri("/v1beta/models/{model}:generateContent", model)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .bodyValue(buildRequestBody(question, snapshot))
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();

            if (logPayloads) {
                // This is the path that has been caught inventing figures, so seeing exactly
                // which numbers it was given is the whole point of being able to read it.
                log.debug("[DEV] >>> advice prompt to {}:\n{}", model, prompt.system(snapshot));
                log.debug("[DEV] >>> question:\n{}", question);
                log.debug("[DEV] <<< reply text:\n{}", response == null ? "(none)"
                        : response.path("candidates").path(0).path("content")
                                .path("parts").path(0).path("text").asText("(no text part)"));
            }
            String answer = readAnswer(response);
            // Duration matters more here than anywhere: this is the slowest call a user
            // waits on, and a slow dependency looks exactly like a broken one.
            log.info("Gemini advice answered in {}ms (model={}, chars={})",
                    System.currentTimeMillis() - startedAt, model,
                    answer == null ? 0 : answer.length());
            return answer;
        } catch (Exception e) {
            log.warn("Gemini advice call failed after {}ms (model={}): {}",
                    System.currentTimeMillis() - startedAt, model, e.toString());
            return null;
        }
    }

    Map<String, Object> buildRequestBody(String question, SpendingSnapshot snapshot) {
        String bounded = question.length() > MAX_QUESTION_CHARS
                ? question.substring(0, MAX_QUESTION_CHARS)
                : question;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("systemInstruction", Map.of("parts", List.of(Map.of("text", prompt.system(snapshot)))));
        body.put("contents", List.of(Map.of("role", "user", "parts", List.of(Map.of("text", bounded)))));
        body.put("generationConfig", Map.of("temperature", temperature));
        return body;
    }

    private String readAnswer(JsonNode response) {
        String content = response == null ? "" : response
                .path("candidates").path(0).path("content").path("parts").path(0).path("text").asText("");
        String trimmed = content.trim();
        if (trimmed.isEmpty()) {
            log.warn("Gemini advice returned an empty reply (model={}, finishReason={})", model,
                    response == null ? "none"
                            : response.path("candidates").path(0).path("finishReason").asText("unknown"));
            return null;
        }
        return trimmed;
    }
}
