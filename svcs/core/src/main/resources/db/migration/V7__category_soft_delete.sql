-- Deleting a category is a status change, not a row removal (§1.5): transactions
-- from earlier months keep referencing it, and a breakdown of last March still has
-- to be able to name the category the money went to.

ALTER TABLE category ADD COLUMN status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE';

ALTER TABLE category ADD CONSTRAINT category_status_check
    CHECK (status IN ('ACTIVE', 'DELETED'));

-- EXISTING ROWS: the unique index below cannot be created while a user holds two
-- categories of the same kind whose names differ only in case, and databases already
-- do. Rather than fail the migration (leaving the app unable to start) or rename
-- someone's category behind their back, the extra copies are retired to DELETED —
-- every row is kept, and the copy that is actually in use is the one that survives:
-- most transactions wins, then the oldest, then the lowest id. Nothing is erased and
-- no transaction changes category.
WITH ranked AS (
    SELECT c.id,
           row_number() OVER (
               PARTITION BY c.user_id, c.kind, lower(c.name)
               ORDER BY (SELECT count(*) FROM transaction t WHERE t.category_id = c.id) DESC,
                        c.created_time ASC NULLS LAST,
                        c.id ASC
           ) AS rn
    FROM category c
    WHERE c.status = 'ACTIVE'
)
UPDATE category SET status = 'DELETED'
WHERE id IN (SELECT id FROM ranked WHERE rn > 1);

-- One live category per (user, kind, name), compared case-insensitively — Food,
-- food and FOOD are one category. Partial on ACTIVE so a name frees up once its
-- category is deleted.
CREATE UNIQUE INDEX uq_category_name_per_kind
    ON category (user_id, kind, lower(name))
    WHERE status = 'ACTIVE';
