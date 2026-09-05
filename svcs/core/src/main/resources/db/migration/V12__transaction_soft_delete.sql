-- Deleting a transaction is a status change, not a row removal (§1.6) — the same rule
-- category has followed since V7, and for a stronger reason: a chat turn records the
-- transaction it created (V3/V11) and a goal contribution records the one that funded
-- it, so a removed row would leave both pointing at nothing.
--
-- Effect on existing rows: every one becomes ACTIVE. That is not a default standing in
-- for an unknown — a row that exists today has not been deleted, so ACTIVE is the true
-- answer for all of them.
--
-- NOT a settlement status. There is no PENDING/PAID here and there must never be: due
-- is derivable from txn_date, and storing it would put an "is it real yet" filter on
-- every aggregate for nothing (§1.10). Deletion is derivable from nothing, which is
-- what makes this column worth its cost.

ALTER TABLE transaction ADD COLUMN status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE transaction ADD CONSTRAINT transaction_status_check
    CHECK (status IN ('ACTIVE', 'DELETED'));

-- Every total now filters on status, and the dashboard reads the (user, date) window
-- on every page load. Making the composite partial keeps deleted rows out of the index
-- entirely rather than out of the result set, so the hot path does not pay for them.
DROP INDEX IF EXISTS idx_transaction_user_date;
CREATE INDEX idx_transaction_user_date ON transaction (user_id, txn_date)
    WHERE status = 'ACTIVE';
