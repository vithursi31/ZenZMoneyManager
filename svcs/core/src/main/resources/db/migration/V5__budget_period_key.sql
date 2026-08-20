-- Budgets become per-period rows (F-1.6): a budget names the exact month (yyyy-MM)
-- or year (yyyy) it applies to, so a limit set in May never silently claims January
-- and a user can run Food at 2000 in July and 3000 in August.

ALTER TABLE budget ADD COLUMN period_key VARCHAR(7);

-- Existing rows adopt the period containing their own creation time (UTC). The table
-- is empty in every environment today, so in practice this updates nothing.
UPDATE budget
SET period_key = CASE period
        WHEN 'YEARLY' THEN to_char(to_timestamp(COALESCE(created_time, 0) / 1000) AT TIME ZONE 'UTC', 'YYYY')
        ELSE to_char(to_timestamp(COALESCE(created_time, 0) / 1000) AT TIME ZONE 'UTC', 'YYYY-MM')
    END
WHERE period_key IS NULL;

ALTER TABLE budget ALTER COLUMN period_key SET NOT NULL;

-- At most one ACTIVE budget per (account, category, period, period_key). A NULL
-- category_id means "overall"; Postgres 14 treats NULLs as distinct in a unique
-- index, so it is folded to '' to make that slot collide as intended.
CREATE UNIQUE INDEX uq_budget_active_slot ON budget
    (user_id, account_id, COALESCE(category_id, ''), period, period_key)
    WHERE status = 'ACTIVE';
