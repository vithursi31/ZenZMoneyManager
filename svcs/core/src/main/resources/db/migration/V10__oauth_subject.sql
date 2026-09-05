-- The provider's stable subject claim, so a social sign-in resolves to the account it
-- resolved to last time. Email alone could not: Apple's private relay address rotates, and
-- matching on it stranded the user with a second, empty account holding none of their
-- transactions. Stored provider-qualified ("apple:0012.abc") so one column serves all three
-- providers and two of them cannot collide on the same opaque id.
--
-- EXISTING ROWS: NULL, and nothing is backfilled. A subject is only knowable from a token,
-- so there is no value to derive — and guessing one would let a row claim a provider identity
-- that is not its own, which is the whole failure this column exists to prevent. A NULL row
-- is adopted on that user's next social sign-in, matched on email exactly as before, so no
-- account is stranded and no user has to do anything.

ALTER TABLE app_user ADD COLUMN oauth_subject VARCHAR(255);

-- One account per provider identity. NULLs are distinct in Postgres, so every pre-existing
-- row stays valid until it is adopted.
CREATE UNIQUE INDEX uq_app_user_oauth_subject ON app_user (oauth_subject);
