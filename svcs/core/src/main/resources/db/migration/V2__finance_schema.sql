-- Part 1 — Core Ledger (MVP). See docs/domain/domain-documentation.md.
-- Money is stored as integer minor units in BIGINT columns, paired with an
-- ISO-4217 currency code. Timestamps and domain dates are epoch-millis BIGINT.

-- User-level currency/language (domain §0.3 / §0.4). App-lock prefs land in Part 4.
ALTER TABLE app_user ADD COLUMN active_currency VARCHAR(3);
ALTER TABLE app_user ADD COLUMN language        VARCHAR(10);

-- A user's account (F-F.1): a container for ledger activity. No balance
-- columns — the user-facing figure is the monthly position, derived from the
-- ledger on read (§1.10). Multiple accounts per user are allowed.
CREATE TABLE account (
    id                VARCHAR(36) PRIMARY KEY,
    user_id           VARCHAR(36) NOT NULL,
    name              VARCHAR(100),
    status            VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    currency          VARCHAR(3) NOT NULL,
    metadata          JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_time      BIGINT,
    modified_time     BIGINT,
    created_by        VARCHAR(120),
    modified_by       VARCHAR(120),
    version           BIGINT
);
CREATE INDEX idx_account_user ON account(user_id);

CREATE TABLE category (
    id                VARCHAR(36) PRIMARY KEY,
    user_id           VARCHAR(36) NOT NULL,
    name              VARCHAR(200) NOT NULL,
    kind              VARCHAR(50) NOT NULL,
    parent_id         VARCHAR(36),
    color             VARCHAR(20),
    icon              VARCHAR(50),
    sort_order        INT NOT NULL DEFAULT 0,
    created_time      BIGINT,
    modified_time     BIGINT,
    created_by        VARCHAR(120),
    modified_by       VARCHAR(120),
    version           BIGINT
);
CREATE INDEX idx_category_user ON category(user_id);
CREATE INDEX idx_category_parent ON category(parent_id);

CREATE TABLE payee (
    id                VARCHAR(36) PRIMARY KEY,
    user_id           VARCHAR(36) NOT NULL,
    name              VARCHAR(300) NOT NULL,
    normalized_name   VARCHAR(300) NOT NULL,
    color             VARCHAR(20),
    icon              VARCHAR(50),
    created_time      BIGINT,
    modified_time     BIGINT,
    created_by        VARCHAR(120),
    modified_by       VARCHAR(120),
    version           BIGINT
);
CREATE INDEX idx_payee_user ON payee(user_id);
CREATE UNIQUE INDEX idx_payee_user_normalized ON payee(user_id, normalized_name);

CREATE TABLE transaction (
    id                    VARCHAR(36) PRIMARY KEY,
    user_id               VARCHAR(36) NOT NULL,
    account_id            VARCHAR(36) NOT NULL,
    type                  VARCHAR(50) NOT NULL,
    category_id           VARCHAR(36),
    amount                BIGINT NOT NULL,
    currency              VARCHAR(3) NOT NULL,
    txn_date              BIGINT NOT NULL,
    payee_id              VARCHAR(36),
    note                  VARCHAR(500),
    tags                  JSONB,
    recurring_id          VARCHAR(36),
    created_time          BIGINT,
    modified_time         BIGINT,
    created_by            VARCHAR(120),
    modified_by           VARCHAR(120),
    version               BIGINT
);
CREATE INDEX idx_transaction_user ON transaction(user_id);
CREATE INDEX idx_transaction_account ON transaction(account_id);
CREATE INDEX idx_transaction_category ON transaction(category_id);
-- The monthly position (§1.10) sums one user's rows over a [from, to) date
-- window on every dashboard read; this composite is the index that serves it.
CREATE INDEX idx_transaction_user_date ON transaction(user_id, txn_date);
CREATE INDEX idx_transaction_payee ON transaction(payee_id);
CREATE INDEX idx_transaction_txn_date ON transaction(txn_date);

CREATE TABLE budget (
    id                VARCHAR(36) PRIMARY KEY,
    user_id           VARCHAR(36) NOT NULL,
    account_id        VARCHAR(36) NOT NULL,
    category_id       VARCHAR(36),
    period            VARCHAR(50) NOT NULL,
    amount_limit      BIGINT NOT NULL,
    rollover          BOOLEAN NOT NULL DEFAULT FALSE,
    status            VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    created_time      BIGINT,
    modified_time     BIGINT,
    created_by        VARCHAR(120),
    modified_by       VARCHAR(120),
    version           BIGINT
);
CREATE INDEX idx_budget_user ON budget(user_id);
CREATE INDEX idx_budget_account ON budget(account_id);
CREATE INDEX idx_budget_category ON budget(category_id);

CREATE TABLE recurring_transaction (
    id                    VARCHAR(36) PRIMARY KEY,
    user_id               VARCHAR(36) NOT NULL,
    account_id            VARCHAR(36) NOT NULL,
    category_id           VARCHAR(36),
    type                  VARCHAR(50) NOT NULL,
    amount                BIGINT NOT NULL,
    currency              VARCHAR(3) NOT NULL,
    cadence               VARCHAR(50) NOT NULL,
    next_run_date         BIGINT NOT NULL,
    anchor_day            INT NOT NULL DEFAULT 1,
    -- Free-trial end for a subscription template (F-1.7); drives the
    -- trial-expiry reminder (F-1.20). Null for anything without a trial.
    trial_end_date        BIGINT,
    end_date              BIGINT,
    active                BOOLEAN NOT NULL DEFAULT TRUE,
    payee_id              VARCHAR(36),
    note                  VARCHAR(500),
    created_time          BIGINT,
    modified_time         BIGINT,
    created_by            VARCHAR(120),
    modified_by           VARCHAR(120),
    version               BIGINT
);
CREATE INDEX idx_recurring_user ON recurring_transaction(user_id);
CREATE INDEX idx_recurring_next_run ON recurring_transaction(next_run_date);

-- Savings goals moved MVP -> Phase 3 (F-3.1) in BRD v1.0; the backend was
-- already built, so the tables stay. Progress is the sum of contributions.
CREATE TABLE savings_goal (
    id                VARCHAR(36) PRIMARY KEY,
    user_id           VARCHAR(36) NOT NULL,
    name              VARCHAR(300) NOT NULL,
    target_amount     BIGINT NOT NULL,
    currency          VARCHAR(3) NOT NULL,
    target_date       BIGINT,
    status            VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    color             VARCHAR(20),
    icon              VARCHAR(50),
    created_time      BIGINT,
    modified_time     BIGINT,
    created_by        VARCHAR(120),
    modified_by       VARCHAR(120),
    version           BIGINT
);
CREATE INDEX idx_savings_goal_user ON savings_goal(user_id);

CREATE TABLE goal_contribution (
    id                VARCHAR(36) PRIMARY KEY,
    goal_id           VARCHAR(36) NOT NULL,
    transaction_id    VARCHAR(36),
    amount            BIGINT NOT NULL,
    contributed_at    BIGINT NOT NULL,
    note              VARCHAR(500),
    created_time      BIGINT,
    modified_time     BIGINT,
    created_by        VARCHAR(120),
    modified_by       VARCHAR(120),
    version           BIGINT
);
CREATE INDEX idx_goal_contribution_goal ON goal_contribution(goal_id);
CREATE INDEX idx_goal_contribution_transaction ON goal_contribution(transaction_id);
