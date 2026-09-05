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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The client's contract is "always answers, never interprets": every transport or
 * parsing failure degrades to {@link LlmExtractionBatch#failed()}, and whatever the
 * model did say is carried through untouched for the resolver to normalize.
 *
 * <p>The shape it reads is an array — one item per money event — because a message can
 * name several. A model that ignores that and answers with the fields inline is still
 * read, as a one-item batch: that is the difference between a capture and "I couldn't
 * read that", and costs nothing to support.
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
        return new OllamaExtractionClient(webClient, MAPPER, PROMPT, "qwen2.5:3b-instruct", 0.1d, "30m", false);
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
                .buildRequestBody("I have spent $5 for burger", List.of("Food & Drinks"), null);

        assertEquals("qwen2.5:3b-instruct", body.get("model"));
        assertEquals(Boolean.FALSE, body.get("stream"));
        assertEquals("json", body.get("format"));
        assertEquals(Boolean.FALSE, body.get("think"),
                "a reasoning monologue is tokens paid for and thrown away");
        assertEquals("30m", body.get("keep_alive"),
                "without it Ollama evicts the model after 5 minutes and the next message "
                        + "pays a ~27s reload it cannot afford");

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

        Map<String, Object> body = clientReturning("{}").buildRequestBody(essay, List.of(), null);

        @SuppressWarnings("unchecked")
        List<Map<String, String>> messages = (List<Map<String, String>>) body.get("messages");
        assertEquals(OllamaExtractionClient.MAX_MESSAGE_CHARS, messages.get(1).get("content").length());
    }

    @Test
    void extract_mapsEveryFieldOfAGoodExtraction() throws Exception {
        String content = """
                {"intent":"CREATE_TRANSACTION","items":[
                  {"kind":"TRANSACTION","txnType":"EXPENSE","amount":"15.50","cadence":null,
                   "categoryGuess":"Groceries","dateExpr":"yesterday","payee":"Keells",
                   "note":"tea things","confidence":0.93}]}
                """;

        LlmExtractionBatch batch = clientReturning(ollamaReply(content))
                .extract("I spent $15.50 at Keells yesterday for tea things", List.of("Groceries"), null);

        assertFalse(batch.isFailed());
        assertEquals(IntentType.CREATE_TRANSACTION, batch.getIntent());
        assertEquals(1, batch.getItems().size());

        LlmExtraction extraction = batch.first();
        assertEquals(IntentType.CREATE_TRANSACTION, extraction.getIntent());
        assertEquals(TransactionType.EXPENSE, extraction.getTxnType());
        assertEquals("15.50", extraction.getAmountRaw(), "the amount stays exact text, never a float");
        assertEquals("Groceries", extraction.getCategoryGuess());
        assertEquals("yesterday", extraction.getDateExpr(), "the phrase is resolved by the backend, not here");
        assertEquals("Keells", extraction.getPayee());
        assertEquals("tea things", extraction.getNote());
        assertEquals(0.93d, extraction.getConfidence());
        assertFalse(extraction.isRecurring());
        assertNull(extraction.getCadence());
    }

    @Test
    void extract_readsOneItemPerAmountNamedInTheMessage() throws Exception {
        String content = """
                {"intent":"CREATE_TRANSACTION","items":[
                  {"kind":"TRANSACTION","txnType":"EXPENSE","amount":"28","note":"coffee","confidence":0.92},
                  {"kind":"TRANSACTION","txnType":"EXPENSE","amount":"350","note":"groceries","confidence":0.92},
                  {"kind":"TRANSACTION","txnType":"EXPENSE","amount":"120","note":"fuel","confidence":0.92}]}
                """;

        LlmExtractionBatch batch = clientReturning(ollamaReply(content))
                .extract("$28 on coffee, $350 on groceries and $120 on fuel", List.of(), null);

        assertEquals(List.of("28", "350", "120"),
                batch.getItems().stream().map(LlmExtraction::getAmountRaw).toList());
    }

    @Test
    void extract_readsARepeatAsATemplateWithItsCadence() throws Exception {
        String content = """
                {"intent":"CREATE_RECURRING","items":[
                  {"kind":"RECURRING","txnType":"EXPENSE","amount":"15","cadence":"MONTHLY",
                   "payee":"Netflix","confidence":0.94}]}
                """;

        LlmExtraction extraction = clientReturning(ollamaReply(content))
                .extract("Netflix 15 every month", List.of(), null).first();

        assertTrue(extraction.isRecurring());
        assertEquals(RecurringCadence.MONTHLY, extraction.getCadence());
        assertEquals(IntentType.CREATE_RECURRING, extraction.getIntent());
    }

    @Test
    void extract_marksARecurringItemInsideAnOtherwiseOrdinaryMessage() throws Exception {
        String content = """
                {"intent":"CREATE_TRANSACTION","items":[
                  {"kind":"TRANSACTION","txnType":"EXPENSE","amount":"28","note":"coffee","confidence":0.9},
                  {"kind":"RECURRING","txnType":"EXPENSE","amount":"15","cadence":"MONTHLY",
                   "payee":"Netflix","confidence":0.9}]}
                """;

        LlmExtractionBatch batch = clientReturning(ollamaReply(content))
                .extract("coffee 28, and Netflix is 15 every month", List.of(), null);

        assertEquals(IntentType.CREATE_TRANSACTION, batch.getItems().get(0).getIntent());
        assertEquals(IntentType.CREATE_RECURRING, batch.getItems().get(1).getIntent(),
                "kind decides per item, so a mixed message is not forced either way");
    }

    @Test
    void extract_readsABareObjectAsAOneItemBatch() throws Exception {
        LlmExtractionBatch batch = clientReturning(ollamaReply(
                "{\"intent\":\"CREATE_TRANSACTION\",\"txnType\":\"EXPENSE\",\"amount\":\"5\",\"confidence\":0.9}"))
                .extract("spent 5 on burger", List.of(), null);

        assertFalse(batch.isFailed(), "a model that ignored the array contract still said something usable");
        assertEquals(1, batch.getItems().size());
        assertEquals("5", batch.first().getAmountRaw());
    }

    @Test
    void extract_capsTheNumberOfItemsOneMessageCanWrite() throws Exception {
        String item = "{\"kind\":\"TRANSACTION\",\"txnType\":\"EXPENSE\",\"amount\":\"1\",\"confidence\":0.9}";
        String content = "{\"intent\":\"CREATE_TRANSACTION\",\"items\":["
                + String.join(",", java.util.Collections.nCopies(OllamaExtractionClient.MAX_ITEMS + 4, item)) + "]}";

        LlmExtractionBatch batch = clientReturning(ollamaReply(content))
                .extract("a very long list", List.of(), null);

        assertEquals(OllamaExtractionClient.MAX_ITEMS, batch.getItems().size(),
                "one message must not be able to write an unbounded number of rows");
    }

    @Test
    void extract_keepsAnAmountTheModelWroteAsANumber() throws Exception {
        LlmExtraction extraction = clientReturning(ollamaReply(
                "{\"intent\":\"CREATE_TRANSACTION\",\"items\":[{\"txnType\":\"EXPENSE\",\"amount\":5,\"confidence\":0.9}]}"))
                .extract("spent 5 on burger", List.of(), null).first();

        assertEquals("5", extraction.getAmountRaw());
    }

    @Test
    void extract_treatsBlankAndLiteralNullFieldsAsAbsent() throws Exception {
        LlmExtraction extraction = clientReturning(ollamaReply(
                "{\"intent\":\"CREATE_TRANSACTION\",\"items\":[{\"payee\":\"null\",\"note\":\"   \",\"categoryGuess\":null}]}"))
                .extract("spent 5 on burger", List.of(), null).first();

        assertNull(extraction.getPayee(), "instruct models write the string \"null\" often enough to matter");
        assertNull(extraction.getNote());
        assertNull(extraction.getCategoryGuess());
        assertNull(extraction.getAmountRaw(), "a missing field is absent, not empty text");
    }

    @Test
    void extract_fallsBackWhenTheModelInventsAnEnumValue() throws Exception {
        LlmExtractionBatch batch = clientReturning(ollamaReply(
                "{\"intent\":\"BUY_SOMETHING\",\"items\":[{\"txnType\":\"SPENDING\",\"amount\":\"5\",\"cadence\":\"FORTNIGHTLY\"}]}"))
                .extract("spent 5 on burger", List.of(), null);

        assertFalse(batch.isFailed(), "a bad guess is not a broken call");
        assertEquals(IntentType.UNKNOWN, batch.getIntent());
        assertNull(batch.first().getTxnType());
        assertNull(batch.first().getCadence());
    }

    @Test
    void extract_clampsAConfidenceGivenAsAPercentage() throws Exception {
        LlmExtraction extraction = clientReturning(ollamaReply(
                "{\"intent\":\"CREATE_TRANSACTION\",\"items\":[{\"confidence\":95}]}"))
                .extract("spent 5 on burger", List.of(), null).first();

        assertEquals(1.0d, extraction.getConfidence());
    }

    @Test
    void extract_returnsNoItemsForAQuestion() throws Exception {
        LlmExtractionBatch batch = clientReturning(ollamaReply("{\"intent\":\"QUERY\",\"items\":[]}"))
                .extract("how much did I spend on food?", List.of(), null);

        assertFalse(batch.isFailed());
        assertEquals(IntentType.QUERY, batch.getIntent());
        assertTrue(batch.isEmpty(), "a question captures nothing");
    }

    @Test
    void extract_failsWhenTheModelAnswersWithProse() throws Exception {
        LlmExtractionBatch batch = clientReturning(ollamaReply("Sure! You spent five dollars."))
                .extract("spent 5 on burger", List.of(), null);

        assertTrue(batch.isFailed());
        assertEquals(IntentType.UNKNOWN, batch.getIntent());
    }

    @Test
    void extract_failsWhenTheModelAnswersWithAnEmptyReply() throws Exception {
        assertTrue(clientReturning(ollamaReply("")).extract("spent 5", List.of(), null).isFailed());
    }

    @Test
    void extract_failsOnAnHttpError() {
        LlmExtractionBatch batch = client(request -> Mono.just(ClientResponse.create(HttpStatus.INTERNAL_SERVER_ERROR)
                        .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                        .body("{\"error\":\"model not found\"}")
                        .build()))
                .extract("spent 5 on burger", List.of(), null);

        assertTrue(batch.isFailed(), "a 5xx from the model must not become a 5xx to the user");
    }

    @Test
    void extract_failsWhenOllamaIsUnreachable() {
        LlmExtractionBatch batch = client(request -> Mono.error(new java.net.ConnectException("connection refused")))
                .extract("spent 5 on burger", List.of(), null);

        assertTrue(batch.isFailed(), "the model is optional infrastructure — being down is not an outage");
    }

    @Test
    void extract_failsOnABlankMessageWithoutCallingTheModel() {
        LlmExtractionBatch batch = client(request -> Mono.error(new AssertionError("must not call the model")))
                .extract("   ", List.of(), null);

        assertTrue(batch.isFailed());
    }
}
