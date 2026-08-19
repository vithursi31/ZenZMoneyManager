package com.zenzmoney.core.repository;

import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.entity.Category;
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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The category breakdown aggregate (F-1.19) against a real Postgres. This query is
 * a GROUP BY with an ad-hoc join across two entities that hold no JPA association —
 * exactly the shape a mock cannot verify.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class CategoryBreakdownQueryTest {

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

    private static final long JAN_2026 = 1_767_225_600_000L;   // 2026-01-01T00:00:00Z
    private static final long FEB_2026 = 1_769_904_000_000L;   // 2026-02-01T00:00:00Z

    @Autowired TransactionRepository transactionRepository;
    @Autowired TestEntityManager em;

    private String category(String name, CategoryKind kind, String color) {
        Category c = new Category();
        c.setUserId("u1");
        c.setName(name);
        c.setKind(kind);
        c.setColor(color);
        c.setIcon("icon-" + name.toLowerCase());
        em.persist(c);
        return c.getId();
    }

    private void txn(String userId, String accountId, String categoryId,
                     TransactionType type, long amount, long txnDate) {
        Transaction t = new Transaction();
        t.setUserId(userId);
        t.setAccountId(accountId);
        t.setCategoryId(categoryId);
        t.setType(type);
        t.setAmount(amount);
        t.setCurrency("USD");
        t.setTxnDate(txnDate);
        em.persist(t);
    }

    private String salary;
    private String rent;
    private String food;

    @BeforeEach
    void seed() {
        salary = category("Salary", CategoryKind.INCOME, "#0f0");
        rent = category("Rent", CategoryKind.EXPENSE, "#f00");
        food = category("Food", CategoryKind.EXPENSE, "#00f");

        txn("u1", "a1", salary, TransactionType.INCOME, 500_000, JAN_2026);
        txn("u1", "a2", salary, TransactionType.INCOME, 75_000, JAN_2026 + 1_000);
        txn("u1", "a1", rent, TransactionType.EXPENSE, 120_000, JAN_2026 + 2_000);
        txn("u1", "a1", food, TransactionType.EXPENSE, 30_000, JAN_2026 + 3_000);
        txn("u1", "a2", food, TransactionType.EXPENSE, 15_005, JAN_2026 + 4_000);
        em.flush();
    }

    private List<CategoryBreakdownRow> january(String accountId) {
        return transactionRepository.categoryTotalsInWindow("u1", JAN_2026, FEB_2026, accountId);
    }

    private CategoryBreakdownRow find(List<CategoryBreakdownRow> rows, String categoryId) {
        return rows.stream().filter(r -> r.getCategoryId().equals(categoryId)).findFirst().orElseThrow();
    }

    /** One row per (category, direction) — five transactions collapse to three buckets. */
    @Test
    void groupsByCategoryAndDirection_summingAmountsAndCounts() {
        List<CategoryBreakdownRow> rows = january(null);

        assertEquals(3, rows.size());
        assertEquals(575_000, find(rows, salary).getAmount());      // 500,000 + 75,000 across two accounts
        assertEquals(2, find(rows, salary).getTransactionCount());
        assertEquals(45_005, find(rows, food).getAmount());         // 30,000 + 15,005
        assertEquals(2, find(rows, food).getTransactionCount());
        assertEquals(120_000, find(rows, rent).getAmount());
    }

    @Test
    void carriesTheCategoryDisplayFields() {
        CategoryBreakdownRow row = find(january(null), rent);

        assertEquals("Rent", row.getName());
        assertEquals("#f00", row.getColor());
        assertEquals("icon-rent", row.getIcon());
        assertEquals(TransactionType.EXPENSE, row.getType());
    }

    @Test
    void ordersBiggestFirst() {
        List<CategoryBreakdownRow> rows = january(null);

        assertEquals(List.of("Salary", "Rent", "Food"), rows.stream().map(CategoryBreakdownRow::getName).toList());
    }

    @Test
    void accountFilter_narrowsToThatAccount() {
        List<CategoryBreakdownRow> rows = january("a2");

        assertEquals(2, rows.size());                          // salary + food only
        assertEquals(75_000, find(rows, salary).getAmount());
        assertEquals(15_005, find(rows, food).getAmount());
        assertTrue(rows.stream().noneMatch(r -> r.getCategoryId().equals(rent)));
    }

    /** The window is half-open, so a row on the upper bound belongs to the next period. */
    @Test
    void excludesRowsOutsideTheWindow() {
        txn("u1", "a1", rent, TransactionType.EXPENSE, 999_999, FEB_2026);
        em.flush();

        assertEquals(120_000, find(january(null), rent).getAmount());
    }

    @Test
    void otherUsersRowsAreNeverCounted() {
        txn("u2", "a1", salary, TransactionType.INCOME, 1_000_000, JAN_2026);
        em.flush();

        assertEquals(575_000, find(january(null), salary).getAmount());
    }

    @Test
    void emptyWindow_returnsNoRows() {
        assertTrue(transactionRepository
                .categoryTotalsInWindow("u1", FEB_2026, FEB_2026 + 1_000, null).isEmpty());
    }
}
