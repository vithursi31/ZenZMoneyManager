-- Deleting a budget is now a status change, not a row removal (§1.7), so
-- budget.status has to accept DELETED.
--
-- The constraint being replaced was never written by a migration: Hibernate's
-- ddl-auto=update generated `budget_status_check` from the enum, so it exists on
-- developer databases and not on a Flyway-only one. Hence DROP IF EXISTS, and the
-- same constraint name on the way back in — reusing it keeps ddl-auto from adding
-- a second, narrower copy.

ALTER TABLE budget DROP CONSTRAINT IF EXISTS budget_status_check;

ALTER TABLE budget ADD CONSTRAINT budget_status_check
    CHECK (status IN ('ACTIVE', 'ARCHIVED', 'DELETED'));
