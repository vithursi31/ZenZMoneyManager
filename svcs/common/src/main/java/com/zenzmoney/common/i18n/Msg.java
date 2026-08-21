package com.zenzmoney.common.i18n;

import static com.zenzmoney.common.i18n.MessageKey.of;

/**
 * Every user-facing sentence the API can answer with, as a key. Text lives in the bundles under
 * {@code core/src/main/resources/i18n/}; the rules for adding one are in CLAUDE.md under
 * "Messages and Languages".
 *
 * <p>A key is not an error code. Codes stay coarse and are the client's contract
 * ({@link com.zenzmoney.common.status.ServiceCodes}); keys are one-per-rejection and decide only
 * what the human reads. Groups below mirror the {@code ServiceCodes} bands.
 */
public interface Msg {

    // ── identity and registration ───────────────────────────────────────────────
    MessageKey EMAIL_REQUIRED          = of("error.auth.email-required");
    MessageKey EMAIL_INVALID           = of("error.auth.email-invalid");
    MessageKey EMAIL_DISPOSABLE        = of("error.auth.email-disposable");
    MessageKey EMAIL_IN_USE            = of("error.auth.email-in-use");
    MessageKey EMAIL_UNKNOWN           = of("error.auth.email-unknown");
    /** {0} = provider name. */
    MessageKey SOCIAL_LOGIN_ONLY       = of("error.auth.social-login-only");
    /** {0} = provider name. */
    MessageKey SOCIAL_NO_PASSWORD      = of("error.auth.social-no-password");
    /** {0} = provider name. */
    MessageKey SOCIAL_NO_RESET         = of("error.auth.social-no-reset");
    MessageKey LOGIN_RATE_LIMITED      = of("error.auth.login-rate-limited");

    // ── password policy ─────────────────────────────────────────────────────────
    MessageKey PASSWORD_EMPTY          = of("error.password.empty");
    MessageKey PASSWORD_WHITESPACE     = of("error.password.whitespace");
    MessageKey PASSWORD_NO_SPECIAL     = of("error.password.no-special");
    MessageKey PASSWORD_NO_LETTER      = of("error.password.no-letter");
    MessageKey PASSWORD_NO_DIGIT       = of("error.password.no-digit");
    /** {0} = minimum length. */
    MessageKey PASSWORD_TOO_SHORT      = of("error.password.too-short");
    /** {0} = maximum length. */
    MessageKey PASSWORD_TOO_LONG       = of("error.password.too-long");

    // ── verification codes ──────────────────────────────────────────────────────
    MessageKey OTP_REQUIRED            = of("error.otp.code-required");
    MessageKey OTP_NONE_PENDING        = of("error.otp.none-pending");
    MessageKey OTP_EXPIRED             = of("error.otp.expired");
    MessageKey OTP_TOO_MANY_ATTEMPTS   = of("error.otp.too-many-attempts");
    MessageKey OTP_INCORRECT           = of("error.otp.incorrect");
    MessageKey OTP_RATE_LIMITED        = of("error.otp.rate-limited");

    // ── social sign-in ──────────────────────────────────────────────────────────
    /** {0} = provider name. */
    MessageKey OAUTH_UNAVAILABLE       = of("error.oauth.provider-unavailable");

    // ── profile and onboarding ──────────────────────────────────────────────────
    /** {0} = the rejected value. */
    MessageKey LANGUAGE_UNSUPPORTED    = of("error.profile.unsupported-language");
    /** {0} = the currency already in force. */
    MessageKey CURRENCY_CHANGE_UNSUPPORTED = of("error.onboarding.currency-change-unsupported");
    /** {0} = the rejected value. */
    MessageKey CURRENCY_UNKNOWN        = of("error.onboarding.unknown-currency");
    /** {0} = the rejected value. */
    MessageKey TIMEZONE_UNKNOWN        = of("error.onboarding.unknown-timezone");

    // ── shared across ledger features ───────────────────────────────────────────
    MessageKey AMOUNT_NOT_POSITIVE     = of("error.common.amount-not-positive");
    MessageKey MONTH_FORMAT            = of("error.common.month-format");
    MessageKey DATE_RANGE_INVERTED     = of("error.common.date-range-inverted");
    MessageKey DATE_RANGE_REQUIRED     = of("error.common.date-range-required");

    // ── account ─────────────────────────────────────────────────────────────────
    MessageKey ACCOUNT_NOT_FOUND       = of("error.account.not-found");
    MessageKey ACCOUNT_RENAME_DELETED  = of("error.account.rename-deleted");
    MessageKey ACCOUNT_ALREADY_DELETED = of("error.account.already-deleted");
    MessageKey ACCOUNT_LAST_ACTIVE     = of("error.account.last-active");
    /** {0} = the currency amounts are already recorded in. */
    MessageKey ACCOUNT_CURRENCY_LOCKED = of("error.account.currency-locked");
    MessageKey ACCOUNT_NO_CURRENCY     = of("error.account.no-active-currency");

    // ── category ────────────────────────────────────────────────────────────────
    MessageKey CATEGORY_NOT_FOUND      = of("error.category.not-found");
    /** {0} = the requested name. */
    MessageKey CATEGORY_DUPLICATE      = of("error.category.duplicate");
    MessageKey CATEGORY_DEPTH_EXCEEDED = of("error.category.depth-exceeded");
    MessageKey CATEGORY_PARENT_KIND    = of("error.category.parent-kind-mismatch");
    MessageKey CATEGORY_ALREADY_DELETED = of("error.category.already-deleted");
    MessageKey CATEGORY_DELETED        = of("error.category.deleted");
    MessageKey CATEGORY_HAS_CHILDREN   = of("error.category.has-children");
    MessageKey CATEGORY_USED_BY_BUDGET = of("error.category.used-by-budget");
    MessageKey CATEGORY_KIND_MISMATCH  = of("error.category.kind-mismatch");

    // ── payee ───────────────────────────────────────────────────────────────────
    MessageKey PAYEE_NOT_FOUND         = of("error.payee.not-found");
    MessageKey PAYEE_DUPLICATE         = of("error.payee.duplicate");
    MessageKey PAYEE_IN_USE            = of("error.payee.in-use");

    // ── transaction ─────────────────────────────────────────────────────────────
    MessageKey TRANSACTION_NOT_FOUND   = of("error.transaction.not-found");
    /** {0} = the rejected value. */
    MessageKey TRANSACTION_UNKNOWN_TYPE = of("error.transaction.unknown-type");

    // ── budget ──────────────────────────────────────────────────────────────────
    MessageKey BUDGET_NOT_FOUND        = of("error.budget.not-found");
    MessageKey BUDGET_ALREADY_DELETED  = of("error.budget.already-deleted");
    MessageKey BUDGET_DELETED          = of("error.budget.deleted");
    MessageKey BUDGET_ACCOUNT_DELETED  = of("error.budget.account-deleted");
    MessageKey BUDGET_CATEGORY_NOT_EXPENSE = of("error.budget.category-not-expense");
    MessageKey BUDGET_LIMIT_NOT_POSITIVE = of("error.budget.limit-not-positive");
    MessageKey BUDGET_DUPLICATE_SLOT   = of("error.budget.duplicate-slot");

    // ── recurring ───────────────────────────────────────────────────────────────
    MessageKey RECURRING_NOT_FOUND     = of("error.recurring.not-found");
    MessageKey RECURRING_NEXT_RUN_INVALID = of("error.recurring.next-run-invalid");
    /** {0} = the maximum window in days. */
    MessageKey RECURRING_UPCOMING_WINDOW_INVALID = of("error.recurring.upcoming-window-invalid");

    // ── savings goal ────────────────────────────────────────────────────────────
    MessageKey GOAL_NOT_FOUND          = of("error.goal.not-found");
    MessageKey GOAL_TARGET_NOT_POSITIVE = of("error.goal.target-not-positive");
    MessageKey GOAL_HAS_CONTRIBUTIONS  = of("error.goal.has-contributions");
    MessageKey GOAL_NO_CURRENCY        = of("error.goal.no-active-currency");
    MessageKey CONTRIBUTION_NOT_FOUND  = of("error.goal.contribution-not-found");
    MessageKey CONTRIBUTION_NOT_POSITIVE = of("error.goal.contribution-not-positive");
    MessageKey CONTRIBUTION_AMOUNT_MISMATCH = of("error.goal.contribution-amount-mismatch");
    MessageKey CONTRIBUTION_CURRENCY_MISMATCH = of("error.goal.contribution-currency-mismatch");

    // ── chat ────────────────────────────────────────────────────────────────────
    MessageKey CHAT_MESSAGE_NOT_FOUND  = of("error.chat.message-not-found");
    MessageKey CHAT_DRAFT_COMMITTED    = of("error.chat.draft-already-committed");
    MessageKey CHAT_DRAFT_CLOSED       = of("error.chat.draft-closed");
    MessageKey CHAT_NO_DRAFT_TO_CHANGE = of("error.chat.no-draft-to-change");
    MessageKey CHAT_DRAFT_ADDED        = of("error.chat.draft-already-added");
    MessageKey CHAT_DRAFT_SUPERSEDED   = of("error.chat.draft-superseded");
    MessageKey CHAT_NO_DRAFT_TO_CONFIRM = of("error.chat.no-draft-to-confirm");
    MessageKey CHAT_DRAFT_INCOMPLETE   = of("error.chat.draft-incomplete");
    MessageKey CHAT_DRAFT_COMMITTED_REJECT = of("error.chat.draft-already-committed-reject");
    MessageKey CHAT_DRAFT_CLOSED_DISCARD = of("error.chat.draft-closed-discard");
    MessageKey CHAT_RATE_LIMITED       = of("error.chat.rate-limited");
    MessageKey CHAT_INSIGHT_RATE_LIMITED = of("error.chat.insight-rate-limited");
}
