CREATE TABLE app_user (
    id                          VARCHAR(36) PRIMARY KEY,
    email                       VARCHAR(255) NOT NULL UNIQUE,
    display_name                VARCHAR(200),
    password_hash               VARCHAR(255) NOT NULL,
    auth_mode                   VARCHAR(50) NOT NULL DEFAULT 'password',
    status                      VARCHAR(50) NOT NULL DEFAULT 'pending',
    timezone                    VARCHAR(50) NOT NULL DEFAULT 'UTC',
    locked                      BOOLEAN NOT NULL DEFAULT FALSE,
    last_login_time             BIGINT,
    login_attempts              INT NOT NULL DEFAULT 0,
    system_generated_password   BOOLEAN NOT NULL DEFAULT FALSE,
    email_verified              BOOLEAN NOT NULL DEFAULT FALSE,
    first_name                  VARCHAR(120),
    last_name                   VARCHAR(120),
    preferences                 JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_time                BIGINT,
    modified_time               BIGINT,
    created_by                  VARCHAR(120),
    modified_by                 VARCHAR(120),
    version                     BIGINT
);

CREATE INDEX idx_app_user_email ON app_user(email);

CREATE TABLE user_roles (
    user_id VARCHAR(36) NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role    VARCHAR(50) NOT NULL,
    PRIMARY KEY (user_id, role)
);

CREATE TABLE verification (
    id              VARCHAR(36) PRIMARY KEY,
    email           VARCHAR(255) NOT NULL,
    code            VARCHAR(10) NOT NULL,
    purpose         VARCHAR(30) NOT NULL,
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    expires_at      BIGINT NOT NULL,
    attempts        INT NOT NULL DEFAULT 0,
    created_time    BIGINT,
    modified_time   BIGINT,
    created_by      VARCHAR(120),
    modified_by     VARCHAR(120),
    version         BIGINT
);

CREATE INDEX idx_verification_lookup ON verification(email, purpose, status);
