--liquibase formatted sql

--changeset nexora:audit-1
CREATE TABLE audit_events (
    event_id uuid PRIMARY KEY,
    entity_id uuid NOT NULL,
    company_id uuid NOT NULL,
    event_type varchar(64) NOT NULL CHECK (event_type IN ('PaymentCreated', 'PaymentProcessing',
        'PaymentSucceeded', 'PaymentFailed', 'PaymentCancelled', 'RefundCompleted')),
    occurred_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    schema_version integer NOT NULL CHECK (schema_version = 1),
    correlation_id varchar(128) NOT NULL,
    payload jsonb NOT NULL
);
CREATE INDEX idx_audit_company_time ON audit_events (company_id, occurred_at DESC, event_id DESC);
CREATE INDEX idx_audit_entity_time ON audit_events (company_id, entity_id, occurred_at DESC, event_id DESC);

--changeset nexora:audit-2 splitStatements:false
CREATE FUNCTION reject_audit_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'Audit events are immutable';
END;
$$ LANGUAGE plpgsql;

--changeset nexora:audit-3
CREATE TRIGGER audit_events_immutable BEFORE UPDATE OR DELETE ON audit_events
    FOR EACH ROW EXECUTE FUNCTION reject_audit_mutation();
