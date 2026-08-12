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
 *
 * <p><b>This is also how subscriptions are modelled</b> (F-1.7): Netflix is a
 * monthly EXPENSE template whose payee is Netflix, whose {@code nextRunDate} is
 * the renewal date, and whose {@code trialEndDate} is set while a free trial runs.
 * There is no separate {@code Subscription} entity — one table cannot
 * double-count a charge against itself.
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

    /** Required; its kind must match {@link #type}. */
    @Column(name = "category_id", nullable = false, length = 36)
    private String categoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private TransactionType type;

    /** Minor units, positive. */
    @Column(nullable = false)
    private long amount;

    @Column(nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private RecurringCadence cadence;

    /** Epoch millis of the next generation. */
    @Column(name = "next_run_date", nullable = false)
    private long nextRunDate;

    /**
     * The user-picked day-of-month (1–31) the schedule anchors to, for MONTHLY/YEARLY
     * cadence. Persisted so a "31st" template clamps to a short month's last day yet
     * returns to the 31st afterwards, instead of walking back off the clamped date
     * (§1.8). Irrelevant for DAILY/WEEKLY.
     */
    @Column(name = "anchor_day", nullable = false)
    private int anchorDay = 1;

    /**
     * Free-trial end for a subscription template (F-1.7), epoch millis; null when
     * there is no trial. Reminders (F-1.20) warn before it passes — the charge
     * itself still comes from {@link #nextRunDate}.
     */
    @Column(name = "trial_end_date")
    private Long trialEndDate;

    /** Nullable; stop generating after this. */
    @Column(name = "end_date")
    private Long endDate;

    @Column(nullable = false)
    private boolean active = true;

    /** Optional FK → payee (§1.5b). Copied onto generated transactions. */
    @Column(name = "payee_id", length = 36)
    private String payeeId;

    @Column(length = 500)
    private String note;
}
