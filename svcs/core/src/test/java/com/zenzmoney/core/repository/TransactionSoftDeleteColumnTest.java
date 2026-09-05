package com.zenzmoney.core.repository;

import com.zenzmoney.common.domain.TransactionStatus;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.entity.Transaction;
import java.util.List;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code V12__transaction_soft_delete.sql} against a real Postgres on the real Flyway
 * schema.
 *
 * <p><b>The aggregates are what this is really guarding.</b> Deleting is soft, so a
 * deleted row is still in the table and every sum has to exclude it — and a sum that
 * forgets is silent, wrong, and lands in the monthly position (§1.10), the figure the
 * whole app is built on. Each of the five totals is asserted against a table holding
 * one live row and one deleted one, so a missing filter fails here rather than in
 * someone's dashboard.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class TransactionSoftDeleteColumnTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:14-alpine");

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "none");
    }

    private static final long JAN_2026 = 1_767_225_600_000L;
    private static final long FEB_2026 = 1_769_904_000_000L;

    @Autowired TransactionRepository repository;
    @Autowired TestEntityManager em;

    /** Every row that existed before the migration has not been deleted, so ACTIVE is true for all. */
    @Test
    void theColumnDefaultsToActive() {
        em.getEntityManager().createNativeQuery(
                        "INSERT INTO transaction (id, user_id, account_id, type, category_id, amount,"
                                + " currency, txn_date) VALUES"
                                + " ('legacy','u1','a1','EXPENSE','c1',1050,'USD',:d)")
                .setParameter("d", JAN_2026)
                .executeUpdate();
        em.flush();
        em.clear();

        assertEquals(TransactionStatus.ACTIVE, repository.findById("legacy").orElseThrow().getStatus(),
                "a row written before the column existed is live, not unknown");
    }

    @Test
    void theCheckConstraintRefusesAStatusTheEnumDoesNotHave() {
        Transaction saved = repository.save(
                txn(TransactionType.EXPENSE, 1_000, JAN_2026, TransactionStatus.ACTIVE));
        em.flush();

        assertThrows(Exception.class, () -> {
            em.getEntityManager()
                    .createNativeQuery("UPDATE transaction SET status = 'ARCHIVED' WHERE id = :id")
                    .setParameter("id", saved.getId())
                    .executeUpdate();
            em.flush();
        }, "a value the enum no longer has must not survive in the column");
    }

    @Test
    void aDeletedRowRoundTripsAndStaysOutOfTheLiveList() {
        repository.save(txn(TransactionType.EXPENSE, 1_000, JAN_2026, TransactionStatus.ACTIVE));
        String deleted = repository.save(
                txn(TransactionType.EXPENSE, 2_000, JAN_2026, TransactionStatus.DELETED)).getId();
        em.flush();
        em.clear();

        assertEquals(TransactionStatus.DELETED, repository.findById(deleted).orElseThrow().getStatus());
        assertEquals(1, repository.findByUserIdAndStatus("u1", TransactionStatus.ACTIVE).size());
        assertTrue(repository.findByIdAndUserId(deleted, "u1").isPresent(),
                "undo has to be able to reach a deleted row");
        assertFalse(repository.findByIdAndUserIdAndStatus(deleted, "u1", TransactionStatus.ACTIVE).isPresent(),
                "every read path must not");
    }

    /** The five totals, each against one live row and one deleted one of the same shape. */
    @Test
    void noAggregateCountsADeletedRow() {
        repository.save(txn(TransactionType.EXPENSE, 1_000, JAN_2026, TransactionStatus.ACTIVE));
        repository.save(txn(TransactionType.EXPENSE, 5_000, JAN_2026, TransactionStatus.DELETED));
        repository.save(txn(TransactionType.INCOME, 3_000, JAN_2026, TransactionStatus.ACTIVE));
        repository.save(txn(TransactionType.INCOME, 7_000, JAN_2026, TransactionStatus.DELETED));
        em.flush();
        em.clear();

        assertEquals(1_000, repository.sumAmountByTypeInWindow(
                "u1", TransactionType.EXPENSE, JAN_2026, FEB_2026, null), "monthly position, expenses");
        assertEquals(3_000, repository.sumAmountByTypeInWindow(
                "u1", TransactionType.INCOME, JAN_2026, FEB_2026, null), "monthly position, income");
        assertEquals(1_000, repository.sumExpenseInWindow(
                "u1", JAN_2026, FEB_2026, null), "overall budget");
        assertEquals(1_000, repository.sumExpenseByCategoryInWindow(
                "u1", "c1", JAN_2026, FEB_2026, null), "category budget");
        assertEquals(1_000, repository.sumExpenseByCategoryInWindowGrouped("u1", JAN_2026, FEB_2026)
                .stream().mapToLong(CategoryTotal::getAmount).sum(), "the assistant's figures");
    }

    /** Live rows only, so a delete request can never offer someone a row that is already gone. */
    @Test
    void theDeleteCandidateFinderSeesOnlyLiveRows() {
        String live = repository.save(
                txn(TransactionType.EXPENSE, 250_000, JAN_2026, TransactionStatus.ACTIVE)).getId();
        repository.save(txn(TransactionType.EXPENSE, 250_000, JAN_2026, TransactionStatus.DELETED));
        em.flush();
        em.clear();

        assertEquals(List.of(live), repository
                .findByUserIdAndStatusAndAmountOrderByTxnDateDesc("u1", TransactionStatus.ACTIVE, 250_000)
                .stream().map(Transaction::getId).toList());
    }

    /** No explicit id — BaseEntity assigns one on persist, so the row is never detached. */
    private static Transaction txn(TransactionType type, long amount, long date,
                                   TransactionStatus status) {
        Transaction t = new Transaction();
        t.setUserId("u1");
        t.setAccountId("a1");
        t.setType(type);
        t.setCategoryId("c1");
        t.setAmount(amount);
        t.setCurrency("USD");
        t.setTxnDate(date);
        t.setStatus(status);
        return t;
    }
}
