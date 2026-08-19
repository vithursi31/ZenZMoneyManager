package com.zenzmoney.core.web.dto;

import com.zenzmoney.common.domain.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.Setter;

/**
 * Answers a suggestion chip, or edits a draft in the preview, before it is
 * confirmed (F-1.11). Every field is optional and only the ones sent are applied,
 * so one tap on "Food & Drinks" and a full edit of four fields use the same call.
 *
 * <p>This path never reaches the model: an answer the user picked is already
 * structured, so re-reading it as language would only add latency and a chance to
 * get it wrong. Neither currency nor account is accepted — the currency is the
 * user's active one and the account is resolved server-side on confirm (§0.3/§1.4).
 */
@Getter
@Setter
public class UpdateChatDraftRequest {

    /** The assistant turn holding the draft. */
    @NotBlank
    private String messageId;

    private TransactionType txnType;

    /** Minor units of the user's active currency; direction comes from {@link #txnType}. */
    @Positive
    private Long amountMinor;

    /** Must be one of the caller's own categories, of the kind matching {@link #txnType}. */
    private String categoryId;

    /** Epoch millis. Omitted leaves the draft's date — today unless the message said otherwise. */
    private Long txnDate;

    @Size(max = 500)
    private String note;

    @Size(max = 300)
    private String payeeName;
}
