--liquibase formatted sql

--changeset nexora:financial-tables
create table payments (
    id uuid primary key,
    company_id uuid not null,
    amount numeric(19,2) not null check (amount > 0),
    currency varchar(3) not null check (currency in ('KZT', 'USD', 'EUR')),
    description varchar(500) not null,
    status varchar(20) not null check (status in ('CREATED', 'PROCESSING', 'SUCCEEDED', 'FAILED', 'CANCELLED')),
    refunded_amount numeric(19,2) not null default 0 check (refunded_amount >= 0 and refunded_amount <= amount),
    scenario varchar(32) not null,
    failure_reason varchar(80),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    version bigint not null default 0,
    unique (id, company_id, currency)
);
create index payments_company_created_idx on payments (company_id, created_at desc, id);
create index payments_company_status_created_idx on payments (company_id, status, created_at desc, id);

create table payment_history (
    id uuid primary key,
    payment_id uuid not null references payments(id),
    status varchar(20) not null,
    actor_id uuid not null,
    reason varchar(80) not null,
    occurred_at timestamptz not null
);
create index payment_history_payment_time_idx on payment_history (payment_id, occurred_at);

create table refunds (
    id uuid primary key,
    company_id uuid not null,
    payment_id uuid not null,
    actor_id uuid not null,
    amount numeric(19,2) not null check (amount > 0),
    currency varchar(3) not null,
    created_at timestamptz not null,
    foreign key (payment_id, company_id, currency) references payments (id, company_id, currency),
    unique (id, payment_id, company_id, currency)
);
create index refunds_payment_created_idx on refunds (payment_id, company_id, created_at);

create table idempotency_records (
    company_id uuid not null,
    idempotency_key varchar(128) not null,
    operation varchar(40) not null,
    fingerprint varchar(64) not null,
    response jsonb not null,
    created_at timestamptz not null default now(),
    primary key (company_id, idempotency_key)
);

create table ledger_accounts (
    id uuid primary key,
    company_id uuid not null,
    currency varchar(3) not null check (currency in ('KZT', 'USD', 'EUR')),
    account_type varchar(30) not null check (account_type in ('PROVIDER_CLEARING', 'MERCHANT_BALANCE')),
    unique (company_id, currency, account_type),
    unique (id, company_id, currency)
);

create table ledger_transactions (
    id uuid primary key,
    company_id uuid not null,
    payment_id uuid not null,
    refund_id uuid,
    currency varchar(3) not null,
    operation varchar(20) not null check (operation in ('PAYMENT', 'REFUND')),
    created_xid bigint not null default txid_current(),
    created_at timestamptz not null,
    check ((operation = 'PAYMENT' and refund_id is null) or (operation = 'REFUND' and refund_id is not null)),
    foreign key (payment_id, company_id, currency) references payments (id, company_id, currency),
    foreign key (refund_id, payment_id, company_id, currency) references refunds (id, payment_id, company_id, currency),
    unique (id, company_id, currency)
);
create unique index ledger_payment_once_idx on ledger_transactions (payment_id) where operation = 'PAYMENT';
create unique index ledger_refund_once_idx on ledger_transactions (refund_id) where operation = 'REFUND';
create index ledger_transactions_company_created_idx on ledger_transactions (company_id, created_at desc, id);

create table ledger_entries (
    id uuid primary key,
    transaction_id uuid not null references ledger_transactions(id),
    account_id uuid not null references ledger_accounts(id),
    side varchar(6) not null check (side in ('DEBIT', 'CREDIT')),
    amount numeric(19,2) not null check (amount > 0),
    currency varchar(3) not null check (currency in ('KZT', 'USD', 'EUR'))
);
create index ledger_entries_transaction_idx on ledger_entries (transaction_id);
create index ledger_entries_account_idx on ledger_entries (account_id);

--changeset nexora:ledger-immutable splitStatements:false
create function reject_financial_mutation() returns trigger language plpgsql as $$
begin
    raise exception 'Posted financial records are immutable; use a compensating operation' using errcode = '23514';
end;
$$;
create trigger ledger_transaction_immutable before update or delete on ledger_transactions
    for each row execute function reject_financial_mutation();
create trigger ledger_entry_immutable before update or delete on ledger_entries
    for each row execute function reject_financial_mutation();
create trigger ledger_account_immutable before update or delete on ledger_accounts
    for each row execute function reject_financial_mutation();
create trigger payment_history_immutable before update or delete on payment_history
    for each row execute function reject_financial_mutation();
create trigger refund_immutable before update or delete on refunds
    for each row execute function reject_financial_mutation();
create trigger idempotency_immutable before update or delete on idempotency_records
    for each row execute function reject_financial_mutation();

--changeset nexora:ledger-balanced splitStatements:false
create function check_ledger_balance() returns trigger language plpgsql as $$
declare
    target_id uuid;
    entry_count bigint;
    balance numeric;
    mismatch_count bigint;
begin
    if tg_table_name = 'ledger_transactions' then
        target_id := new.id;
    else
        target_id := new.transaction_id;
    end if;
    select count(*), coalesce(sum(case when side = 'DEBIT' then amount else -amount end), 0)
        into entry_count, balance from ledger_entries where transaction_id = target_id;
    if entry_count < 2 or balance <> 0 then
        raise exception 'Ledger transaction % is not balanced', target_id using errcode = '23514';
    end if;
    select count(*) into mismatch_count
        from ledger_entries e join ledger_accounts a on a.id = e.account_id
        join ledger_transactions t on t.id = e.transaction_id
        where t.id = target_id and (e.currency <> t.currency or a.currency <> t.currency or a.company_id <> t.company_id);
    if mismatch_count <> 0 then
        raise exception 'Ledger company and currency must match all entries' using errcode = '23514';
    end if;
    return new;
end;
$$;
create constraint trigger ledger_transaction_balanced after insert on ledger_transactions
    deferrable initially deferred for each row execute function check_ledger_balance();
create constraint trigger ledger_entry_balanced after insert on ledger_entries
    deferrable initially deferred for each row execute function check_ledger_balance();

--changeset nexora:ledger-closed-transactions splitStatements:false
create function reject_posted_entry_append() returns trigger language plpgsql as $$
begin
    if not exists (select 1 from ledger_transactions where id = new.transaction_id and created_xid = txid_current()) then
        raise exception 'Entries may only be added when their transaction is first posted' using errcode = '23514';
    end if;
    return new;
end;
$$;
create trigger ledger_entry_append_guard before insert on ledger_entries
    for each row execute function reject_posted_entry_append();
