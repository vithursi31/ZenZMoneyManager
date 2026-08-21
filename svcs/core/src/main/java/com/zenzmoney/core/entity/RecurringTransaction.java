package com.zenzmoney.core.entity;

import com.zenzmoney.common.domain.BaseEntity;
import com.zenzmoney.common.domain.PaymentMethod;
import com.zenzmoney.common.domain.RecurringCadence;
import com.zenzmoney.common.domain.TransactionType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@Entity
@Table(name = "recurring_transaction")
public class RecurringTransaction extends BaseEntity {

    @Column(name = "user_id", nullable = false, length = 36)
    private String userId;

    @Column(name = "account_id", nullable = false, length = 36)
    private String accountId;

    @Column(name = "category_id", nullable = false, length = 36)
    private String categoryId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private TransactionType type;

    @Column(nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private RecurringCadence cadence;

    @Column(name = "next_run_date", nullable = false)
    private long nextRunDate;

    @Column(name = "anchor_day", nullable = false)
    private int anchorDay = 1;

    @Column(name = "trial_end_date")
    private Long trialEndDate;

    @Column(name = "end_date")
    private Long endDate;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "payee_id", length = 36)
    private String payeeId;

    @Column(length = 500)
    private String note;

    @Enumerated(EnumType.STRING)
    @Column(name = "payment_method", length = 50)
    private PaymentMethod paymentMethod;
}
