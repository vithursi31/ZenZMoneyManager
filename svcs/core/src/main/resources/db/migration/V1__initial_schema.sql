-- Habits
CREATE TABLE habit (
    id                  VARCHAR(36) PRIMARY KEY,
    user_id             VARCHAR(36) NOT NULL,
    name                VARCHAR(300) NOT NULL,
    description         VARCHAR(1000),
    category            VARCHAR(100),
    frequency           VARCHAR(50) NOT NULL DEFAULT 'daily',
    target_per_period   INT NOT NULL DEFAULT 1,
    color               VARCHAR(20),
    icon                VARCHAR(50),
    status              VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    sort_order          INT NOT NULL DEFAULT 0,
    metadata            JSONB DEFAULT '{}',
    created_time        BIGINT NOT NULL,
    modified_time       BIGINT,
    created_by          VARCHAR(36),
    modified_by         VARCHAR(36),
    version             BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_habit_user ON habit(user_id);
CREATE INDEX idx_habit_user_status ON habit(user_id, status);
