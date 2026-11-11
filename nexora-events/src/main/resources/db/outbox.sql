--liquibase formatted sql

--changeset nexora:outbox-1
CREATE TABLE outbox_events (
    sequence bigint GENERATED ALWAYS AS IDENTITY UNIQUE NOT NULL,
    event_id uuid PRIMARY KEY,
    entity_id uuid NOT NULL,
    company_id uuid NOT NULL,
    event_type varchar(64) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    next_attempt_at timestamptz NOT NULL DEFAULT now(),
    last_error varchar(128)
);
CREATE INDEX idx_outbox_pending ON outbox_events (next_attempt_at, sequence) WHERE published_at IS NULL;
CREATE INDEX idx_outbox_entity_pending ON outbox_events (entity_id, sequence) WHERE published_at IS NULL;
