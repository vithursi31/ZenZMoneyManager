package com.zenzmoney.core.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenzmoney.core.service.insight.SpendingSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Same contract as the extraction client — always answers, never throws — but the
 * output is prose for a person rather than JSON for the backend, and a model that is
 * down must cost the user an apology instead of a 5xx.
 */
class OllamaAdviceClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final AdvicePrompt PROMPT =
            new AdvicePrompt(new ClassPathResource("prompts/advice-system.md"));

    private static final SpendingSnapshot SNAPSHOT = new SpendingSnapshot("USD", "UTC", List.of(
            new SpendingSnapshot.MonthSpend("2026-08", 300_000L, 120_000L,
                    List.of(new SpendingSnapshot.CategorySpend("c-food", "Food & Drinks", 60_000L)))));

    // --- the request ---

    @Test
    void buildRequestBody_asksForOneNonStreamedProseAnswer() {
        Map<String, Object> body = clientReturning("{}").buildRequestBody("how can I spend less?", SNAPSHOT);

        assertEquals(false, body.get("stream"));
        assertFalse(body.containsKey("format"),
                "JSON mode is for extraction; an answer for a person is a sentence");

        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) body.get("options");
        assertEquals(0.3d, options.get("temperature"));
        assertEquals(400, options.get("num_predict"));
    }

    @Test
    void buildRequestBody_sendsTheUsersFiguresInTheSystemPromptAndTheQuestionAsTheUserTurn() {
        Map<String, Object> body = clientReturning("{}").buildRequestBody("how can I spend less?", SNAPSHOT);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> messages = (List<Map<String, String>>) body.get("messages");
        assertTrue(messages.get(0).get("content").contains("Food & Drinks: 600.00 USD"));
        assertEquals("how can I spend less?", messages.get(1).get("content"));
    }

    @Test
    void buildRequestBody_boundsTheQuestionSoPromptSizeStaysBounded() {
        String essay = "a".repeat(OllamaAdviceClient.MAX_QUESTION_CHARS + 200);

        Map<String, Object> body = clientReturning("{}").buildRequestBody(essay, SNAPSHOT);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> messages = (List<Map<String, String>>) body.get("messages");
        assertEquals(OllamaAdviceClient.MAX_QUESTION_CHARS, messages.get(1).get("content").length());
    }

    // --- the answer ---

    @Test
    void answer_returnsTheModelsTextTrimmed() throws Exception {
        String answer = clientReturning(ollamaReply("  You spent 600.00 USD on Food & Drinks.  "))
                .answer("where does my money go?", SNAPSHOT);

        assertEquals("You spent 600.00 USD on Food & Drinks.", answer);
    }

    @Test
    void answer_isNullWhenTheModelSaysNothing() throws Exception {
        assertNull(clientReturning(ollamaReply("   ")).answer("where does my money go?", SNAPSHOT));
    }

    @Test
    void answer_isNullRatherThanThrowingWhenTheModelIsUnreachable() {
        OllamaAdviceClient client = client(request -> Mono.error(new java.net.ConnectException("refused")));

        assertNull(client.answer("where does my money go?", SNAPSHOT),
                "a down model costs an apology, not a 5xx (§9)");
    }

    @Test
    void answer_isNullRatherThanThrowingOnAServerError() {
        OllamaAdviceClient client = client(request -> Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body("{}")
                .build()));

        assertNull(client.answer("where does my money go?", SNAPSHOT));
    }

    @Test
    void answer_refusesToCallTheModelWithNothingToAnswerFrom() {
        assertNull(clientReturning("{}").answer("  ", SNAPSHOT));
        assertNull(clientReturning("{}").answer("where does my money go?", null));
    }

    // --- fixtures ---

    private OllamaAdviceClient clientReturning(String ollamaBody) {
        return client(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(ollamaBody)
                .build()));
    }

    private OllamaAdviceClient client(ExchangeFunction exchange) {
        WebClient webClient = WebClient.builder()
                .baseUrl("http://ollama.test:11434")
                .exchangeFunction(exchange)
                .build();
        return new OllamaAdviceClient(webClient, PROMPT, "qwen2.5:3b-instruct", 0.3d);
    }

    private static String ollamaReply(String modelContent) throws Exception {
        return MAPPER.writeValueAsString(Map.of(
                "model", "qwen2.5:3b-instruct",
                "message", Map.of("role", "assistant", "content", modelContent),
                "done", true));
    }
}
