--liquibase formatted sql

--changeset nexora:identity-1
CREATE TABLE companies (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    registration_number VARCHAR(64) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE app_users (
    id UUID PRIMARY KEY,
    email VARCHAR(254) NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('ADMIN', 'MERCHANT', 'AUDITOR')),
    company_id UUID REFERENCES companies(id),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    CHECK ((role = 'ADMIN' AND company_id IS NULL) OR (role <> 'ADMIN' AND company_id IS NOT NULL))
);
CREATE INDEX idx_users_company ON app_users(company_id);

CREATE TABLE access_tokens (
    token_hash VARCHAR(64) PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES app_users(id),
    expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX idx_tokens_user ON access_tokens(user_id);
CREATE INDEX idx_tokens_expiry ON access_tokens(expires_at);
