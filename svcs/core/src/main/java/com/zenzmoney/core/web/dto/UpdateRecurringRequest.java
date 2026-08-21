package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.PaymentMethod;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Partial update — a null field means "leave unchanged". Type, account, category,
 * and cadence are the template's identity and are not editable here; recreate the
 * template to change them. Setting {@code active} false pauses generation; setting
 * {@code nextRunDate} reschedules and re-anchors the monthly/yearly day-of-month.
 */
@Getter
@Setter
public class UpdateRecurringRequest {

    @Positive
    private Long amount;

    private Long nextRunDate;

    /** Free-trial end date for a subscription (F-1.7). */
    private Long trialEndDate;

    private Long endDate;

    /** Pause (false) or resume (true) generation. */
    private Boolean active;

    @Size(max = 300)
    private String payeeName;

    @Size(max = 500)
    private String note;

    /** Optional label for how the money moved; omit when the user did not say. Not an account. */
    private PaymentMethod paymentMethod;
}
