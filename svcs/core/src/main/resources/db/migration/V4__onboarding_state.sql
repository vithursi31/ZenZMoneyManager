-- Onboarding becomes an explicit state (F-1.27).
--
-- Registration now seeds a provisional currency from the locale the client reports,
-- so "active_currency IS NOT NULL" no longer distinguishes a value the user chose
-- from one guessed for them. This flag carries that distinction: FALSE means the
-- preferences are still provisional and onboarding may overwrite the currency;
-- TRUE means the user confirmed it and it is frozen (§0.3).

ALTER TABLE app_user ADD COLUMN onboarded BOOLEAN NOT NULL DEFAULT FALSE;

-- Existing rows: OnboardingService was until now the only writer of active_currency,
-- so every user holding one had already completed onboarding and must not be sent
-- back through it. Users without one stay FALSE, which is where they already stood.
UPDATE app_user SET onboarded = TRUE WHERE active_currency IS NOT NULL;
