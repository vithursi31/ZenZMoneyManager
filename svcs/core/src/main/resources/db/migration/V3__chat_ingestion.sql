-- Part 3 — Chat-based transaction entry (F-1.9a). See docs/domain/domain-documentation.md §3.4
-- and docs/features/chat-transaction-entry-plan.md §7.
--
-- Effect on existing rows: none. This migration only adds a new table; no
-- existing table, column, or row is touched.
--
-- The draft (ParsedIntent, §3.3) is embedded as jsonb rather than given its own
-- table: it is written once, read as a whole, and never queried by field.
-- transaction_id is left un-constrained (no FK) so deleting a transaction never
-- blocks on, or silently rewrites, the conversation history that produced it —
-- the transcript is an audit record and outlives the row it created.

CREATE TABLE chat_message (
    id                VARCHAR(36) PRIMARY KEY,
    user_id           VARCHAR(36) NOT NULL,
    role              VARCHAR(50) NOT NULL,
    content           VARCHAR(2000) NOT NULL,
    language          VARCHAR(10),
    parsed_intent     JSONB,
    status            VARCHAR(50) NOT NULL,
    transaction_id    VARCHAR(36),
    session_id        VARCHAR(36),
    created_time      BIGINT,
    modified_time     BIGINT,
    created_by        VARCHAR(120),
    modified_by       VARCHAR(120),
    version           BIGINT
);
CREATE INDEX idx_chat_message_user ON chat_message(user_id);
CREATE INDEX idx_chat_message_session ON chat_message(user_id, session_id);
CREATE INDEX idx_chat_message_transaction ON chat_message(transaction_id);
