package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.BudgetPeriod;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Create a budget (§1.7). Currency is not sent — it is taken from the user's
 * active currency (§0.3). A null {@code categoryId} means an overall budget;
 * otherwise it must reference an owned EXPENSE category.
 */
@Getter
@Setter
public class CreateBudgetRequest {

    /** Null ⇒ overall budget; otherwise an owned EXPENSE category. */
    private String categoryId;

    @NotNull
    private BudgetPeriod period;

    /** Minor-unit cap for the period. */
    @Positive
    private long amountLimit;

    /** Epoch millis anchoring the period cycle. Defaults to now when omitted (null/0). */
    private Long startDate;

    /** Carry unused amount into the next period. */
    private boolean rollover;

    /** Optional; only used when there is no active currency yet (ISO-4217). */
    @Size(min = 3, max = 3)
    private String currency;
}
