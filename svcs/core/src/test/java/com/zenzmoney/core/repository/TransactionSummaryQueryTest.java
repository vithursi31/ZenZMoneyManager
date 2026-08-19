package com.zenzmoney.core.repository;

import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.entity.Transaction;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * The monthly-position aggregate (§1.10) against a real Postgres on the real Flyway
 * schema. Mocks can't check that the JPQL parses, that the nullable account filter
 * behaves, or that the window boundary is genuinely half-open in SQL — and those are
 * the three ways this query silently returns a wrong number.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TransactionSummaryQueryTest {

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

    private static final long JAN_2026 = 1_767_225_600_000L;   // 2026-01-01T00:00:00Z
    private static final long FEB_2026 = 1_769_904_000_000L;   // 2026-02-01T00:00:00Z

    @Autowired TransactionRepository transactionRepository;
    @Autowired TestEntityManager em;

    private void save(String userId, String accountId, TransactionType type, long amount, long txnDate) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setType(type);
        t.setAmount(amount);
        t.setCurrency("USD");
        t.setTxnDate(txnDate);
        em.persist(t);
    }

    @BeforeEach
    void seed() {
        save("u1", "a1", TransactionType.INCOME, 500_000, JAN_2026);
        save("u1", "a1", TransactionType.EXPENSE, 30_000, JAN_2026 + 1_000);
        save("u1", "a2", TransactionType.INCOME, 200_000, JAN_2026 + 2_000);
        save("u1", "a2", TransactionType.EXPENSE, 12_000, JAN_2026 + 3_000);
        em.flush();
    }

    @Test
    void nullAccount_sumsAcrossEveryAccountTheUserHolds() {
        long income = transactionRepository.sumAmountByTypeInWindow(
                "u1", TransactionType.INCOME, JAN_2026, FEB_2026, null);
        long expenses = transactionRepository.sumAmountByTypeInWindow(
                "u1", TransactionType.EXPENSE, JAN_2026, FEB_2026, null);

        assertEquals(700_000, income);      // a1 + a2
        assertEquals(42_000, expenses);
    }

    @Test
    void accountId_narrowsToThatAccountAlone() {
        long income = transactionRepository.sumAmountByTypeInWindow(
                "u1", TransactionType.INCOME, JAN_2026, FEB_2026, "a2");
        long expenses = transactionRepository.sumAmountByTypeInWindow(
                "u1", TransactionType.EXPENSE, JAN_2026, FEB_2026, "a2");

        assertEquals(200_000, income);
        assertEquals(12_000, expenses);
    }

    /** No rows must sum to 0, never null — the dashboard renders this straight into a figure. */
    @Test
    void unknownAccount_sumsToZero_notNull() {
        assertEquals(0, transactionRepository.sumAmountByTypeInWindow(
                "u1", TransactionType.INCOME, JAN_2026, FEB_2026, "nope"));
    }

    /**
     * The window is {@code [from, to)}. A row stamped exactly on the upper bound belongs
     * to the next month only — if it were inclusive, February's income would also appear
     * in January's and the two months would not sum to the year.
     */
    @Test
    void windowIsHalfOpen_upperBoundExcluded_lowerBoundIncluded() {
        save("u1", "a1", TransactionType.INCOME, 999_999, FEB_2026);   // first instant of February
        em.flush();

        long january = transactionRepository.sumAmountByTypeInWindow(
                "u1", TransactionType.INCOME, JAN_2026, FEB_2026, null);
        long february = transactionRepository.sumAmountByTypeInWindow(
                "u1", TransactionType.INCOME, FEB_2026, FEB_2026 + 1, null);

        assertEquals(700_000, january);     // the February row is not in January
        assertEquals(999_999, february);    // and the lower bound is included
    }

    @Test
    void otherUsersRowsAreNeverCounted() {
        save("u2", "a1", TransactionType.INCOME, 1_000_000, JAN_2026);
        em.flush();

        assertEquals(700_000, transactionRepository.sumAmountByTypeInWindow(
                "u1", TransactionType.INCOME, JAN_2026, FEB_2026, null));
    }
}
