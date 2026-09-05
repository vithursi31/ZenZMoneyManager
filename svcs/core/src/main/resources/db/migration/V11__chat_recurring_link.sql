-- Chat direct entry (F-1.11, F-1.7) — see docs/features/chat-direct-entry-plan.md §5.
--
-- Effect on existing rows: none. One nullable column is added and every existing
-- row keeps NULL, which is the honest answer for them — no historical chat turn
-- ever created a recurring template, because chat could not create one.
--
-- No FK, for the same reason transaction_id has none (V3): the transcript is an
-- audit record and outlives whatever it created, so deleting a template must not
-- block on, or silently rewrite, the conversation that produced it.
--
-- The draft's new `cadence` field needs no DDL — parsed_intent is jsonb.
-- chat_message.status has no CHECK constraint, so the CREATED/UNDONE values the
-- code now writes need no widening here.

ALTER TABLE chat_message ADD COLUMN recurring_id VARCHAR(36);
CREATE INDEX idx_chat_message_recurring ON chat_message(recurring_id);
