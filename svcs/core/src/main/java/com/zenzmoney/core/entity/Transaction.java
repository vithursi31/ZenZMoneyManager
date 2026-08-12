package com.zenzmoney.core.entity;

import com.zenzmoney.common.domain.BaseEntity;
import com.zenzmoney.common.domain.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * The core ledger record — one row per movement of money (§1.6). {@code amount}
 * is always a positive magnitude; its sign comes from {@code type}.
 *
 * <p>Nothing is cached from this row: the monthly position is summed from the
 * ledger on read (§1.10), and {@code txnDate} alone decides which month a row
 * counts in.
 */
@Getter
@Setter
@Entity
@Table(name = "transaction")
public class Transaction extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    /** The owner's single account (§1.4). Resolved server-side, never client-supplied. */
    @Column(name = "account_id", nullable = false, length = 36)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private TransactionType type;

    /** Required — every transaction is categorized, and the kind must match {@link #type}. */
    @Column(name = "category_id", nullable = false, length = 36)
    private String categoryId;

    /** Minor units. Always positive; sign is derived from {@link #type}. */
    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Epoch millis of the transaction date; decides the row's calendar month (§1.10). */
    @Column(name = "txn_date", nullable = false)
    private long txnDate;

    /** Optional FK → payee (§1.5b). Null for unnamed one-off entries. */
    @Column(name = "payee_id", length = 36)
    private String payeeId;

    @Column(length = 500)
    private String note;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> tags = new ArrayList<>();

    /** Set when this row was generated from a recurring template. */
    @Column(name = "recurring_id", length = 36)
    private String recurringId;
}
