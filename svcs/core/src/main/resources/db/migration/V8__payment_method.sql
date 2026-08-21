-- How the money moved (§1.6, F-1.3): the "Payment" row on the entry screen —
-- cash, card, bank transfer, wallet. It is a label on the row, not a place money
-- is kept: where activity is tracked stays the account (§1.4), so this adds no
-- balance and nothing sums by it.
--
-- EXISTING ROWS: left NULL, which is the stored form of "the user did not say".
-- Every row already in the ledger was recorded before the field existed, so any
-- backfilled value would be a guess presented to the user as their own answer.
-- Nullable rather than DEFAULT for the same reason.

ALTER TABLE transaction ADD COLUMN payment_method VARCHAR(50);

ALTER TABLE transaction ADD CONSTRAINT transaction_payment_method_check
    CHECK (payment_method IS NULL
           OR payment_method IN ('CASH', 'CARD', 'BANK_TRANSFER', 'WALLET', 'OTHER'));

-- The template carries it too, and copies it onto every row it generates (§1.8) —
-- a subscription billed to a card should not produce rows with no method.
ALTER TABLE recurring_transaction ADD COLUMN payment_method VARCHAR(50);

ALTER TABLE recurring_transaction ADD CONSTRAINT recurring_payment_method_check
    CHECK (payment_method IS NULL
           OR payment_method IN ('CASH', 'CARD', 'BANK_TRANSFER', 'WALLET', 'OTHER'));
