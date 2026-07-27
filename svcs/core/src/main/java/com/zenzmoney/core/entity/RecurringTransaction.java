package com.zenzmoney.core.entity;

import com.zenzmoney.common.domain.BaseEntity;
import com.zenzmoney.common.domain.RecurringCadence;
import com.zenzmoney.common.domain.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A template that generates {@link Transaction} rows on a schedule (§1.8). A job
 * scans {@code active = true AND next_run_date <= now}, creates a transaction,
 * then advances {@code nextRunDate} by the cadence.
 */
@Getter
@Setter
@Entity
@Table(name = "recurring_transaction")
public class RecurringTransaction extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "account_id", nullable = false, length = 36)
    private String accountId;

    /** Required unless {@link #type} is TRANSFER. */
    @Column(name = "category_id", length = 36)
    private String categoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private TransactionType type;

    /** Minor units, positive. */
    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    /** Set only for TRANSFER templates. */
    @Column(name = "transfer_account_id", length = 36)
    private String transferAccountId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private RecurringCadence cadence;

    /** Epoch millis of the next generation. */
    @Column(name = "next_run_date", nullable = false)
    private long nextRunDate;

    /** Nullable; stop generating after this. */
    @Column(name = "end_date")
    private Long endDate;

    @Column(nullable = false)
    private boolean active = true;

    @Column(length = 300)
    private String payee;

    @Column(length = 500)
    private String note;
}
