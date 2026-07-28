package com.zenzmoney.core.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.domain.TransactionType;
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
 * The client's contract is "always answers, never interprets": every transport or
 * parsing failure degrades to {@link LlmExtraction#failed()}, and whatever the model
 * did say is carried through untouched for the resolver to normalize.
 */
class OllamaExtractionClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ExtractionPrompt PROMPT =
            new ExtractionPrompt(new ClassPathResource("prompts/extraction-system.md"));

    /** Builds a client whose HTTP call returns {@code ollamaBody} verbatim. */
    private OllamaExtractionClient clientReturning(String ollamaBody) {
        return client(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(ollamaBody)
                .build()));
    }

    private OllamaExtractionClient client(ExchangeFunction exchange) {
        WebClient webClient = WebClient.builder()
                .baseUrl("http://ollama.test:11434")
                .exchangeFunction(exchange)
                .build();
        return new OllamaExtractionClient(webClient, MAPPER, PROMPT, "qwen2.5:3b-instruct", 0.1d);
    }

    /** Wraps the model's answer the way Ollama's /api/chat does. */
    private static String ollamaReply(String modelContent) throws Exception {
        return MAPPER.writeValueAsString(Map.of(
                "model", "qwen2.5:3b-instruct",
                "message", Map.of("role", "assistant", "content", modelContent),
                "done", true));
    }

    @Test
    void buildRequestBody_asksForOneNonStreamedJsonAnswer() {
        Map<String, Object> body = clientReturning("{}")
                .buildRequestBody("I have spent $5 for burger", List.of("Food & Drinks"));

        assertEquals("qwen2.5:3b-instruct", body.get("model"));
        assertEquals(Boolean.FALSE, body.get("stream"));
        assertEquals("json", body.get("format"));

        @SuppressWarnings("unchecked")
        Map<String, Object> options = (Map<String, Object>) body.get("options");
        assertEquals(0.1d, options.get("temperature"));

        @SuppressWarnings("unchecked")
        List<Map<String, String>> messages = (List<Map<String, String>>) body.get("messages");
        assertEquals(2, messages.size());
        assertEquals("system", messages.get(0).get("role"));
        assertTrue(messages.get(0).get("content").contains("Food & Drinks"),
                "the user's categories are the closed list the model guesses from");
        assertEquals("user", messages.get(1).get("role"));
        assertEquals("I have spent $5 for burger", messages.get(1).get("content"));
    }

    @Test
    void buildRequestBody_truncatesAnOverlongMessage() {
        String essay = "a".repeat(OllamaExtractionClient.MAX_MESSAGE_CHARS + 250);

        Map<String, Object> body = clientReturning("{}").buildRequestBody(essay, List.of());

        @SuppressWarnings("unchecked")
        List<Map<String, String>> messages = (List<Map<String, String>>) body.get("messages");
        assertEquals(OllamaExtractionClient.MAX_MESSAGE_CHARS, messages.get(1).get("content").length());
    }

    @Test
    void extract_mapsEveryFieldOfAGoodExtraction() throws Exception {
        String content = """
                {"intent":"CREATE_TRANSACTION","txnType":"EXPENSE","amount":"15.50",
                 "categoryGuess":"Groceries","dateExpr":"yesterday","payee":"Keells",
                 "note":"tea things","confidence":0.93}
                """;

        LlmExtraction extraction = clientReturning(ollamaReply(content))
                .extract("I spent $15.50 at Keells yesterday for tea things", List.of("Groceries"));

        assertFalse(extraction.isFailed());
        assertEquals(IntentType.CREATE_TRANSACTION, extraction.getIntent());
        assertEquals(TransactionType.EXPENSE, extraction.getTxnType());
        assertEquals("15.50", extraction.getAmountRaw(), "the amount stays exact text, never a float");
        assertEquals("Groceries", extraction.getCategoryGuess());
        assertEquals("yesterday", extraction.getDateExpr(), "the phrase is resolved by the backend, not here");
        assertEquals("Keells", extraction.getPayee());
        assertEquals("tea things", extraction.getNote());
        assertEquals(0.93d, extraction.getConfidence());
    }

    @Test
    void extract_keepsAnAmountTheModelWroteAsANumber() throws Exception {
        LlmExtraction extraction = clientReturning(ollamaReply(
                "{\"intent\":\"CREATE_TRANSACTION\",\"txnType\":\"EXPENSE\",\"amount\":5,\"confidence\":0.9}"))
                .extract("spent 5 on burger", List.of());

        assertEquals("5", extraction.getAmountRaw());
    }

    @Test
    void extract_treatsBlankAndLiteralNullFieldsAsAbsent() throws Exception {
        LlmExtraction extraction = clientReturning(ollamaReply(
                "{\"intent\":\"CREATE_TRANSACTION\",\"payee\":\"null\",\"note\":\"   \",\"categoryGuess\":null}"))
                .extract("spent 5 on burger", List.of());

        assertNull(extraction.getPayee(), "instruct models write the string \"null\" often enough to matter");
        assertNull(extraction.getNote());
        assertNull(extraction.getCategoryGuess());
        assertNull(extraction.getAmountRaw(), "a missing field is absent, not empty text");
    }

    @Test
    void extract_fallsBackWhenTheModelInventsAnEnumValue() throws Exception {
        LlmExtraction extraction = clientReturning(ollamaReply(
                "{\"intent\":\"BUY_SOMETHING\",\"txnType\":\"SPENDING\",\"amount\":\"5\"}"))
                .extract("spent 5 on burger", List.of());

        assertFalse(extraction.isFailed(), "a bad guess is not a broken call");
        assertEquals(IntentType.UNKNOWN, extraction.getIntent());
        assertNull(extraction.getTxnType());
    }

    @Test
    void extract_clampsAConfidenceGivenAsAPercentage() throws Exception {
        LlmExtraction extraction = clientReturning(ollamaReply(
                "{\"intent\":\"CREATE_TRANSACTION\",\"confidence\":95}"))
                .extract("spent 5 on burger", List.of());

        assertEquals(1.0d, extraction.getConfidence());
    }

    @Test
    void extract_failsWhenTheModelAnswersWithProse() throws Exception {
        LlmExtraction extraction = clientReturning(ollamaReply("Sure! You spent five dollars."))
                .extract("spent 5 on burger", List.of());

        assertTrue(extraction.isFailed());
        assertEquals(IntentType.UNKNOWN, extraction.getIntent());
    }

    @Test
    void extract_failsWhenTheModelAnswersWithAnEmptyReply() throws Exception {
        LlmExtraction extraction = clientReturning(ollamaReply("")).extract("spent 5", List.of());

        assertTrue(extraction.isFailed());
    }

    @Test
    void extract_failsOnAnHttpError() {
        LlmExtraction extraction = client(request -> Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"error\":\"model not found\"}")
                        .build()))
                .extract("spent 5 on burger", List.of());

        assertTrue(extraction.isFailed(), "a 5xx from the model must not become a 5xx to the user");
    }

    @Test
    void extract_failsWhenOllamaIsUnreachable() {
        LlmExtraction extraction = client(request -> Mono.error(new java.net.ConnectException("connection refused")))
                .extract("spent 5 on burger", List.of());

        assertTrue(extraction.isFailed(), "the model is optional infrastructure — being down is not an outage");
    }

    @Test
    void extract_failsOnABlankMessageWithoutCallingTheModel() {
        LlmExtraction extraction = client(request -> Mono.error(new AssertionError("must not call the model")))
                .extract("   ", List.of());

        assertTrue(extraction.isFailed());
    }
}
