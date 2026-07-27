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
 * is always a positive magnitude; effect on balance is derived from {@code type}.
 */
@Getter
@Setter
@Entity
@Table(name = "transaction")
public class Transaction extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    /** Source account. */
    @Column(name = "account_id", nullable = false, length = 36)
    private String accountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private TransactionType type;

    /** Required for INCOME/EXPENSE, null for TRANSFER. */
    @Column(name = "category_id", length = 36)
    private String categoryId;

    /** Minor units. Always positive; sign is derived from {@link #type}. */
    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Set only when {@link #type} is TRANSFER; the destination account. */
    @Column(name = "transfer_account_id", length = 36)
    private String transferAccountId;

    /** Epoch millis of the transaction date. */
    @Column(name = "txn_date", nullable = false)
    private long txnDate;

    @Column(length = 300)
    private String payee;

    @Column(length = 500)
    private String note;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> tags = new ArrayList<>();

    /** Set when this row was generated from a recurring template. */
    @Column(name = "recurring_id", length = 36)
    private String recurringId;
}
