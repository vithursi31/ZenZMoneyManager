package com.zenzmoney.core.entity;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.domain.TransactionType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ParsedIntent} is stored in a {@code jsonb} column, and Hibernate
 * round-trips a JSON-mapped POJO through its format mapper to snapshot it for
 * dirty checking — so the draft must survive serialize-then-deserialize or the
 * <em>insert</em> fails, not just a later read.
 *
 * <p>Hibernate uses its own {@link ObjectMapper}, not the Spring-configured one,
 * so this test deliberately uses a bare mapper with Jackson's defaults —
 * including {@code FAIL_ON_UNKNOWN_PROPERTIES}. A derived getter with no matching
 * setter is exactly what breaks under those defaults.
 */
class ParsedIntentSerializationTest {

    private static final ObjectMapper HIBERNATE_LIKE_MAPPER = new ObjectMapper();

    @Test
    void survivesTheRoundTripHibernateDoesOnInsert() throws Exception {
        ParsedIntent draft = new ParsedIntent();
        draft.setIntent(IntentType.CREATE_TRANSACTION);
        draft.setTxnType(TransactionType.EXPENSE);
        draft.setAmountMinor(1550L);
        draft.setCurrency("USD");
        draft.setCategoryId("c1");
        draft.setCategoryGuess("Groceries");
        draft.setTxnDate(1_800_000_000_000L);
        draft.setPayeeName("Keells");
        draft.setNote("tea things");
        draft.setConfidence(0.93);

        String json = HIBERNATE_LIKE_MAPPER.writeValueAsString(draft);
        ParsedIntent back = HIBERNATE_LIKE_MAPPER.readValue(json, ParsedIntent.class);

        assertEquals(IntentType.CREATE_TRANSACTION, back.getIntent());
        assertEquals(TransactionType.EXPENSE, back.getTxnType());
        assertEquals(1550L, back.getAmountMinor());
        assertEquals("USD", back.getCurrency());
        assertEquals("c1", back.getCategoryId());
        assertEquals("Groceries", back.getCategoryGuess());
        assertEquals(1_800_000_000_000L, back.getTxnDate());
        assertEquals("Keells", back.getPayeeName());
        assertEquals("tea things", back.getNote());
        assertEquals(0.93, back.getConfidence());
        assertTrue(back.isComplete());
    }

    @Test
    void carriesMissingFieldsThroughTheRoundTrip() throws Exception {
        ParsedIntent draft = new ParsedIntent();
        draft.setMissingFields(List.of("amount", "category"));

        ParsedIntent back = HIBERNATE_LIKE_MAPPER.readValue(
                HIBERNATE_LIKE_MAPPER.writeValueAsString(draft), ParsedIntent.class);

        assertEquals(List.of("amount", "category"), back.getMissingFields());
        assertTrue(!back.isComplete());
    }

    /**
     * {@code complete} is derived from {@code missingFields}. Persisting it would
     * let the stored copy disagree with its own source of truth, so it must not
     * appear in the column at all.
     */
    @Test
    void doesNotPersistTheDerivedCompleteFlag() throws Exception {
        String json = HIBERNATE_LIKE_MAPPER.writeValueAsString(new ParsedIntent());

        assertTrue(!json.contains("\"complete\""),
                () -> "derived getter leaked into the stored json: " + json);
    }
}
