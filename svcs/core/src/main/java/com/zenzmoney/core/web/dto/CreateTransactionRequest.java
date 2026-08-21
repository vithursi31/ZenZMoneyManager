package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.PaymentMethod;
import com.zenzmoney.common.domain.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

import java.util.List;

/**
 * Create a transaction (§1.6). Neither currency nor account is sent: the currency
 * comes from the user's active currency (§0.3) and the account is the user's only
 * one, resolved server-side (§1.4). {@code categoryId} is required and its kind
 * must match {@code type}.
 */
@Getter
@Setter
public class CreateTransactionRequest {

    @NotNull
    private TransactionType type;

    /** Required; its kind must match {@code type} (INCOME→INCOME, EXPENSE→EXPENSE). */
    @NotBlank
    private String categoryId;

    /** Minor units, positive magnitude; direction is derived from type. */
    @Positive
    private long amount;

    /** Epoch millis; defaults to now when omitted (null/0). */
    private Long txnDate;

    /** Optional merchant/payer name; resolved to a Payee (§1.5b). */
    @Size(max = 300)
    private String payeeName;

    @Size(max = 500)
    private String note;

    private List<String> tags;

    /** Optional label for how the money moved; omit when the user did not say. Not an account. */
    private PaymentMethod paymentMethod;
}
