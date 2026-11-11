--liquibase formatted sql

--changeset nexora:financial-tenant-relations
ALTER TABLE payments ADD CONSTRAINT payments_company_fk FOREIGN KEY (company_id) REFERENCES companies(id);
ALTER TABLE ledger_accounts ADD CONSTRAINT ledger_accounts_company_fk FOREIGN KEY (company_id) REFERENCES companies(id);
ALTER TABLE idempotency_records ADD CONSTRAINT idempotency_company_fk FOREIGN KEY (company_id) REFERENCES companies(id);
ALTER TABLE payment_history ADD CONSTRAINT payment_history_actor_fk FOREIGN KEY (actor_id) REFERENCES app_users(id);
ALTER TABLE refunds ADD CONSTRAINT refunds_actor_fk FOREIGN KEY (actor_id) REFERENCES app_users(id);
