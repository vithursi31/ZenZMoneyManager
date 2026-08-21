-- A recurring template is a rule about money kept in an account, and the account
-- already carries the currency (§1.4) — so the template stored a third copy of one
-- fact (the user's active currency being the second). It is now read from the
-- account it posts to, the same way a budget's currency has been read since V5.
--
-- EXISTING ROWS: nothing to migrate. Every value in this column equalled the
-- owning account's currency by construction — it was only ever written from the
-- user's active currency at create time, and re-denominating an account is refused
-- once any amount exists (AccountService.redenominate). Dropping the column
-- therefore loses no information.
--
-- Generated transactions keep their own currency column: a ledger row records what
-- the money WAS, which is a historical fact and not derivable from today's account.

ALTER TABLE recurring_transaction DROP COLUMN currency;
