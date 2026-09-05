package com.zenzmoney.core.repository;

import com.zenzmoney.common.domain.ChatMessageStatus;
import com.zenzmoney.common.domain.ChatRole;
import com.zenzmoney.common.domain.IntentType;
import com.zenzmoney.common.domain.RecurringCadence;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.entity.ChatMessage;
import com.zenzmoney.core.entity.ParsedIntent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * {@code V11__chat_recurring_link.sql} against a real Postgres on the real Flyway
 * schema — what {@code ddl-auto} would create locally proves nothing about what a
 * fresh database gets.
 *
 * <p>Three things can only break here: {@code recurring_id} existing at all, NULL
 * staying legal for every turn written before chat could create a template, and the
 * draft's new {@code cadence} field surviving the jsonb round trip — that one has no
 * DDL of its own, which is exactly why a column-shaped assumption about it would go
 * unnoticed.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class ChatRecurringLinkColumnTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:14-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        // Flyway owns the schema here, exactly as it does in prd — not ddl-auto.
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    private static final long JAN_2026 = 1_767_225_600_000L;

    @Autowired ChatMessageRepository chatMessageRepository;
    @Autowired TestEntityManager em;

    @Test
    void aTurnCanCarryTheTemplateItCreatedAndTheRowItPosted() {
        ChatMessage turn = turn(ChatMessageStatus.CREATED, recurringDraft());
        turn.setRecurringId("r1");
        // A template already due posts its first occurrence on creation, so undo has two
        // rows to remove and the turn has to remember both.
        turn.setTransactionId("t9");

        ChatMessage saved = chatMessageRepository.save(turn);
        em.flush();
        em.clear();

        ChatMessage read = chatMessageRepository.findById(saved.getId()).orElseThrow();
        assertEquals("r1", read.getRecurringId());
        assertEquals("t9", read.getTransactionId());
    }

    /** Null is the honest answer for every turn written before chat could create one. */
    @Test
    void recurringIdIsNullableForAnOrdinaryTransactionTurn() {
        ChatMessage turn = turn(ChatMessageStatus.CREATED, transactionDraft());
        turn.setTransactionId("t1");

        ChatMessage saved = chatMessageRepository.save(turn);
        em.flush();
        em.clear();

        assertNull(chatMessageRepository.findById(saved.getId()).orElseThrow().getRecurringId());
    }

    /**
     * {@code cadence} was added to the embedded draft, which is jsonb and took no
     * migration. That is the reason to assert it: a field with no DDL is the one nobody
     * thinks to check against a real database.
     */
    @Test
    void theDraftsCadenceSurvivesTheJsonbRoundTrip() {
        ChatMessage saved = chatMessageRepository.save(turn(ChatMessageStatus.CREATED, recurringDraft()));
        em.flush();
        em.clear();

        ParsedIntent read = chatMessageRepository.findById(saved.getId()).orElseThrow().getParsedIntent();
        assertEquals(RecurringCadence.MONTHLY, read.getCadence());
        assertEquals(IntentType.CREATE_RECURRING, read.getIntent());
        assertEquals(1500L, read.getAmountMinor());
    }

    /** The new statuses are enum-backed VARCHAR with no CHECK on this table (V3). */
    @Test
    void theNewStatusesStore() {
        for (ChatMessageStatus status : new ChatMessageStatus[] {
                ChatMessageStatus.CREATED, ChatMessageStatus.UNDONE }) {
            ChatMessage saved = chatMessageRepository.save(turn(status, transactionDraft()));
            em.flush();
            em.clear();

            assertEquals(status, chatMessageRepository.findById(saved.getId()).orElseThrow().getStatus());
        }
    }

    private static ChatMessage turn(ChatMessageStatus status, ParsedIntent draft) {
        ChatMessage m = new ChatMessage();
        m.setUserId("u1");
        m.setSessionId("s1");
        m.setRole(ChatRole.ASSISTANT);
        m.setContent("chat.added");
        m.setStatus(status);
        m.setParsedIntent(draft);
        return m;
    }

    private static ParsedIntent transactionDraft() {
        ParsedIntent draft = new ParsedIntent();
        draft.setIntent(IntentType.CREATE_TRANSACTION);
        draft.setTxnType(TransactionType.EXPENSE);
        draft.setAmountMinor(4886L);
        draft.setCurrency("USD");
        draft.setCategoryId("c1");
        draft.setTxnDate(JAN_2026);
        return draft;
    }

    private static ParsedIntent recurringDraft() {
        ParsedIntent draft = transactionDraft();
        draft.setIntent(IntentType.CREATE_RECURRING);
        draft.setAmountMinor(1500L);
        draft.setCadence(RecurringCadence.MONTHLY);
        return draft;
    }
}
