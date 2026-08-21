package com.zenzmoney.core.repository;

import com.zenzmoney.common.domain.PaymentMethod;
import com.zenzmoney.common.domain.RecurringCadence;
import com.zenzmoney.common.domain.TransactionType;
import com.zenzmoney.core.entity.RecurringTransaction;
import com.zenzmoney.core.entity.Transaction;
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
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code V8__payment_method.sql} against a real Postgres on the real Flyway schema —
 * what {@code ddl-auto} would create locally proves nothing about what a fresh
 * database gets. Three things can only break here: the columns existing at all, the
 * CHECK constraints admitting exactly the enum's values, and NULL staying legal for
 * every row written before the field existed.
 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Testcontainers
class PaymentMethodColumnTest {

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

    @Autowired TransactionRepository transactionRepository;
    @Autowired RecurringTransactionRepository recurringRepository;
    @Autowired TestEntityManager em;

    private Transaction transaction(PaymentMethod method) {
        Transaction t = new Transaction();
        t.setUserId("u1");
        t.setAccountId("a1");
        t.setType(TransactionType.EXPENSE);
        t.setCategoryId("c1");
        t.setAmount(1_050);
        t.setCurrency("USD");
        t.setTxnDate(JAN_2026);
        t.setPaymentMethod(method);
        return t;
    }

    private RecurringTransaction template(PaymentMethod method) {
        RecurringTransaction r = new RecurringTransaction();
        r.setUserId("u1");
        r.setAccountId("a1");
        r.setType(TransactionType.EXPENSE);
        r.setCategoryId("c1");
        r.setAmount(9_900);
        r.setCadence(RecurringCadence.MONTHLY);
        r.setNextRunDate(JAN_2026);
        r.setAnchorDay(1);
        r.setPaymentMethod(method);
        return r;
    }

    @Test
    void everyEnumValueRoundTripsOnBothTables() {
        for (PaymentMethod method : PaymentMethod.values()) {
            Transaction t = transactionRepository.save(transaction(method));
            RecurringTransaction r = recurringRepository.save(template(method));
            em.flush();
            em.clear();

            assertEquals(method, transactionRepository.findById(t.getId()).orElseThrow().getPaymentMethod());
            assertEquals(method, recurringRepository.findById(r.getId()).orElseThrow().getPaymentMethod());
        }
    }

    /** Unspecified is the stored form of "the user did not say" — not a default value. */
    @Test
    void nullIsAcceptedOnBothTables() {
        Transaction t = transactionRepository.save(transaction(null));
        RecurringTransaction r = recurringRepository.save(template(null));
        em.flush();
        em.clear();

        assertNull(transactionRepository.findById(t.getId()).orElseThrow().getPaymentMethod());
        assertNull(recurringRepository.findById(r.getId()).orElseThrow().getPaymentMethod());
    }

    /**
     * The CHECK is what stops a value the enum no longer has surviving in the column —
     * a rename in Java would otherwise leave unreadable rows behind, found months later.
     */
    @Test
    void anUnknownValueIsRejectedByTheDatabase() {
        Transaction t = transactionRepository.save(transaction(PaymentMethod.CARD));
        em.flush();

        assertThrows(Exception.class, () -> {
            em.getEntityManager()
                    .createNativeQuery("UPDATE transaction SET payment_method = 'CHEQUE' WHERE id = :id")
                    .setParameter("id", t.getId())
                    .executeUpdate();
            em.flush();
        });
    }
}
