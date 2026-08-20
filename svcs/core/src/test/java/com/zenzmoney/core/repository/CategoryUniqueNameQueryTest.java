package com.zenzmoney.core.repository;

import com.zenzmoney.common.domain.CategoryKind;
import com.zenzmoney.common.domain.CategoryStatus;
import com.zenzmoney.core.entity.Category;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * "Food, food and FOOD are one category" (§1.5) against a real Postgres. Both halves
 * of that rule live outside Java — {@code IgnoreCase} becomes {@code lower(...)} in
 * SQL, and the last-resort guard is the partial unique index from {@code V7} — so a
 * mock can prove neither.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class CategoryUniqueNameQueryTest {

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

    @Autowired CategoryRepository categoryRepository;
    @Autowired TestEntityManager em;

    private Category category(String userId, String name, CategoryKind kind, CategoryStatus status) {
        Category c = new Category();
        c.setUserId(userId);
        c.setName(name);
        c.setKind(kind);
        c.setStatus(status);
        return c;
    }

    private Category persisted(String userId, String name, CategoryKind kind, CategoryStatus status) {
        Category c = category(userId, name, kind, status);
        em.persist(c);
        em.flush();
        return c;
    }

    @Test
    void finder_matchesAnyCasing() {
        persisted("u1", "Food & Drinks", CategoryKind.EXPENSE, CategoryStatus.ACTIVE);

        for (String probe : List.of("Food & Drinks", "food & drinks", "FOOD & DRINKS", "fOOd & DrInKs")) {
            List<Category> hits = categoryRepository.findByUserIdAndKindAndNameIgnoreCaseAndStatus(
                    "u1", CategoryKind.EXPENSE, probe, CategoryStatus.ACTIVE);
            assertEquals(1, hits.size(), "no match for " + probe);
        }
    }

    @Test
    void finder_isScopedToTheUser() {
        persisted("u1", "Food", CategoryKind.EXPENSE, CategoryStatus.ACTIVE);

        assertTrue(categoryRepository.findByUserIdAndKindAndNameIgnoreCaseAndStatus(
                "u2", CategoryKind.EXPENSE, "food", CategoryStatus.ACTIVE).isEmpty());
    }

    @Test
    void index_rejectsASecondLiveCategoryDifferingOnlyInCase() {
        persisted("u1", "Food", CategoryKind.EXPENSE, CategoryStatus.ACTIVE);

        assertThrows(DataIntegrityViolationException.class, () -> categoryRepository.saveAndFlush(
                category("u1", "FOOD", CategoryKind.EXPENSE, CategoryStatus.ACTIVE)));
    }

    /** Gifts received and gifts given are different things; the kind keeps them apart. */
    @Test
    void index_allowsTheSameNameInTheOtherKind() {
        persisted("u1", "Gifts", CategoryKind.EXPENSE, CategoryStatus.ACTIVE);

        Category income = categoryRepository.saveAndFlush(
                category("u1", "gifts", CategoryKind.INCOME, CategoryStatus.ACTIVE));

        assertEquals(CategoryKind.INCOME, income.getKind());
    }

    /** The index is partial on ACTIVE, so deleting a category frees its name. */
    @Test
    void index_allowsReuseOnceTheHolderIsDeleted() {
        persisted("u1", "Food", CategoryKind.EXPENSE, CategoryStatus.DELETED);

        Category fresh = categoryRepository.saveAndFlush(
                category("u1", "FOOD", CategoryKind.EXPENSE, CategoryStatus.ACTIVE));

        assertEquals("FOOD", fresh.getName());
    }

    @Test
    void index_allowsTwoDeletedRowsWithTheSameName() {
        persisted("u1", "Food", CategoryKind.EXPENSE, CategoryStatus.DELETED);

        Category second = categoryRepository.saveAndFlush(
                category("u1", "food", CategoryKind.EXPENSE, CategoryStatus.DELETED));

        assertEquals(CategoryStatus.DELETED, second.getStatus());
    }
}
