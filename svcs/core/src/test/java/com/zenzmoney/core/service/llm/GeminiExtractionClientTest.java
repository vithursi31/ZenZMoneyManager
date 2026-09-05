package com.zenzmoney.core.service.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.domain.RecurringCadence;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The Gemini client answers the same contract as the Ollama one — same
 * {@link LlmExtractionBatch}, same never-throws rule — over a different wire format.
 * These assert the differences that could silently break it: where the reply text
 * lives in the envelope, that the prompt goes in {@code systemInstruction}, and that
 * the response schema is declared so the shape cannot come back wrong.
 *
 * <p>No network: every case runs against a stubbed exchange function.
 */
class GeminiExtractionClientTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final ExtractionPrompt PROMPT =
            new ExtractionPrompt(new ClassPathResource("prompts/extraction-system.md"));

    private GeminiExtractionClient clientReturning(String geminiBody) {
        return client(request -> Mono.just(ClientResponse.create(HttpStatus.OK)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .body(geminiBody)
                .build()));
    }

    private GeminiExtractionClient client(ExchangeFunction exchange) {
        WebClient webClient = WebClient.builder()
                .baseUrl("https://gemini.test")
                .exchangeFunction(exchange)
                .build();
        return new GeminiExtractionClient(webClient, MAPPER, PROMPT,
                "gemini-3.5-flash-lite", 0.1d, false);
    }

    /** Wraps the model's text the way generateContent does. */
    private static String geminiReply(String modelText) throws Exception {
        return MAPPER.writeValueAsString(Map.of("candidates", List.of(
                Map.of("content", Map.of("role", "model",
                        "parts", List.of(Map.of("text", modelText))),
                        "finishReason", "STOP"))));
    }

    // --- the request ---

    @Test
    void buildRequestBody_putsThePromptInSystemInstructionAndAsksForOurSchema() {
        Map<String, Object> body = clientReturning("{}")
                .buildRequestBody("I have spent 5 for burger", List.of("Food & Drinks"), null);

        @SuppressWarnings("unchecked")
        Map<String, Object> system = (Map<String, Object>) body.get("systemInstruction");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> systemParts = (List<Map<String, String>>) system.get("parts");
        assertTrue(systemParts.get(0).get("text").contains("Food & Drinks"),
                "the user's categories are the closed list the model guesses from");

        @SuppressWarnings("unchecked")
        Map<String, Object> generation = (Map<String, Object>) body.get("generationConfig");
        assertEquals("application/json", generation.get("responseMimeType"));
        assertNotNull(generation.get("responseSchema"),
                "a schema is what makes the shape the API's problem instead of the prompt's");
        assertEquals(0.1d, generation.get("temperature"));
    }

    @Test
    void buildRequestBody_truncatesAnOverlongMessage() {
        String essay = "a".repeat(GeminiExtractionClient.MAX_MESSAGE_CHARS + 250);

        Map<String, Object> body = clientReturning("{}").buildRequestBody(essay, List.of(), null);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contents = (List<Map<String, Object>>) body.get("contents");
        @SuppressWarnings("unchecked")
        List<Map<String, String>> parts = (List<Map<String, String>>) contents.get(0).get("parts");
        assertEquals(GeminiExtractionClient.MAX_MESSAGE_CHARS, parts.get(0).get("text").length());
    }

    // --- the reply ---

    @Test
    void extract_mapsEveryFieldOfAGoodExtraction() throws Exception {
        String content = """
                {"intent":"CREATE_TRANSACTION","items":[
                  {"kind":"TRANSACTION","txnType":"EXPENSE","amount":"15.50","cadence":null,
                   "categoryGuess":"Groceries","dateExpr":"yesterday","payee":"Keells",
                   "note":"tea things","confidence":0.93}]}
                """;

        LlmExtractionBatch batch = clientReturning(geminiReply(content))
                .extract("I spent 15.50 at Keells yesterday", List.of("Groceries"), null);

        assertFalse(batch.isFailed());
        assertEquals(IntentType.CREATE_TRANSACTION, batch.getIntent());

        LlmExtraction extraction = batch.first();
        assertEquals(TransactionType.EXPENSE, extraction.getTxnType());
        assertEquals("15.50", extraction.getAmountRaw(), "the amount stays exact text, never a float");
        assertEquals("Groceries", extraction.getCategoryGuess());
        assertEquals("yesterday", extraction.getDateExpr(), "the phrase is resolved by the backend");
        assertEquals("Keells", extraction.getPayee());
        assertEquals(0.93d, extraction.getConfidence());
    }

    @Test
    void extract_readsOneItemPerAmount() throws Exception {
        String content = """
                {"intent":"CREATE_TRANSACTION","items":[
                  {"kind":"TRANSACTION","txnType":"EXPENSE","amount":"28","note":"coffee","confidence":0.9},
                  {"kind":"TRANSACTION","txnType":"EXPENSE","amount":"350","note":"groceries","confidence":0.9}]}
                """;

        LlmExtractionBatch batch = clientReturning(geminiReply(content))
                .extract("28 on coffee and 350 on groceries", List.of(), null);

        assertEquals(List.of("28", "350"),
                batch.getItems().stream().map(LlmExtraction::getAmountRaw).toList());
    }

    @Test
    void extract_readsARepeatAsATemplateWithItsCadence() throws Exception {
        String content = """
                {"intent":"CREATE_RECURRING","items":[
                  {"kind":"RECURRING","txnType":"EXPENSE","amount":"15","cadence":"MONTHLY",
                   "payee":"Netflix","confidence":0.94}]}
                """;

        LlmExtraction extraction = clientReturning(geminiReply(content))
                .extract("Netflix 15 every month", List.of(), null).first();

        assertTrue(extraction.isRecurring());
        assertEquals(RecurringCadence.MONTHLY, extraction.getCadence());
        assertEquals(IntentType.CREATE_RECURRING, extraction.getIntent());
    }

    @Test
    void extract_returnsNoItemsForAQuestion() throws Exception {
        LlmExtractionBatch batch = clientReturning(geminiReply("{\"intent\":\"QUERY\",\"items\":[]}"))
                .extract("how much did I spend on food?", List.of(), null);

        assertFalse(batch.isFailed());
        assertEquals(IntentType.QUERY, batch.getIntent());
        assertTrue(batch.isEmpty(), "a question captures nothing");
    }

    @Test
    void extract_capsTheNumberOfItemsOneMessageCanWrite() throws Exception {
        String item = "{\"kind\":\"TRANSACTION\",\"txnType\":\"EXPENSE\",\"amount\":\"1\",\"confidence\":0.9}";
        String content = "{\"intent\":\"CREATE_TRANSACTION\",\"items\":["
                + String.join(",", java.util.Collections.nCopies(GeminiExtractionClient.MAX_ITEMS + 4, item))
                + "]}";

        LlmExtractionBatch batch = clientReturning(geminiReply(content))
                .extract("a very long list", List.of(), null);

        assertEquals(GeminiExtractionClient.MAX_ITEMS, batch.getItems().size(),
                "one message must not be able to write an unbounded number of rows");
    }

    // --- failure paths: none of them may throw (§9) ---

    /** A blocked or empty candidate list is the shape a safety filter produces. */
    @Test
    void extract_failsWhenThereIsNoCandidate() throws Exception {
        LlmExtractionBatch batch = clientReturning(
                MAPPER.writeValueAsString(Map.of("candidates", List.of())))
                .extract("spent 5 on burger", List.of(), null);

        assertTrue(batch.isFailed());
        assertEquals(IntentType.UNKNOWN, batch.getIntent());
    }

    @Test
    void extract_failsWhenTheModelAnswersWithProse() throws Exception {
        assertTrue(clientReturning(geminiReply("Sure! You spent five dollars."))
                .extract("spent 5 on burger", List.of(), null).isFailed());
    }

    /** A bad key is a 401; it must reach the user as "try again", not a 5xx. */
    @Test
    void extract_failsOnAnUnauthorizedResponse() {
        LlmExtractionBatch batch = client(request -> Mono.just(
                ClientResponse.create(HttpStatus.UNAUTHORIZED)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"error\":{\"message\":\"API key not valid\"}}")
                        .build()))
                .extract("spent 5 on burger", List.of(), null);

        assertTrue(batch.isFailed());
    }

    @Test
    void extract_failsOnAQuotaResponse() {
        LlmExtractionBatch batch = client(request -> Mono.just(
                ClientResponse.create(HttpStatus.TOO_MANY_REQUESTS)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"error\":{\"message\":\"Resource exhausted\"}}")
                        .build()))
                .extract("spent 5 on burger", List.of(), null);

        assertTrue(batch.isFailed(), "a quota refusal must not become a 5xx to the user");
    }

    @Test
    void extract_failsWhenTheApiIsUnreachable() {
        assertTrue(client(request -> Mono.error(new java.net.ConnectException("connection refused")))
                .extract("spent 5 on burger", List.of(), null).isFailed());
    }

    @Test
    void extract_failsOnABlankMessageWithoutCallingTheApi() {
        assertTrue(client(request -> Mono.error(new AssertionError("must not call the API")))
                .extract("   ", List.of(), null).isFailed());
    }

    @Test
    void extract_treatsBlankAndLiteralNullFieldsAsAbsent() throws Exception {
        LlmExtraction extraction = clientReturning(geminiReply(
                "{\"intent\":\"CREATE_TRANSACTION\",\"items\":[{\"payee\":\"null\",\"note\":\"   \"}]}"))
                .extract("spent 5 on burger", List.of(), null).first();

        assertNull(extraction.getPayee());
        assertNull(extraction.getNote());
        assertNull(extraction.getAmountRaw());
    }
}
