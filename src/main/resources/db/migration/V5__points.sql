-- Points ledger. Every customer is a points member: there is no separate enrolment, and a customer's
-- account is created by their first posting. Double-entry: each transaction's entries sum to zero,
-- customer balances never go negative, and transactions and entries are never changed.

-- The id shared with Points API clients (Sota). Random, so it reveals nothing about the customer, and
-- never the internal id or the Keycloak subject. The default fills existing rows.
alter table customer add column public_id uuid not null default gen_random_uuid();
alter table customer add constraint customer_public_id_key unique (public_id);

-- One account per customer, plus one per system kind holding the other side of postings: ISSUED for
-- credits, REDEEMED for debits.
create table points_account (
    id          bigint generated always as identity primary key,
    kind        varchar(16) not null,
    customer_id bigint references customer (id),
    -- Cached for customer accounts only. Every posting touches a system account, so caching theirs
    -- would queue every posting on one row; theirs are summed from the entries when needed.
    balance     bigint,
    -- Bumped by every posting to the account, under its row lock, so feed consumers can order a
    -- customer's balances.
    seq         bigint,
    constraint points_account_kind_check check (kind in ('CUSTOMER', 'ISSUED', 'REDEEMED')),
    constraint points_account_customer_check check (
        (kind = 'CUSTOMER') = (customer_id is not null)
        and (kind = 'CUSTOMER') = (balance is not null)
        and (kind = 'CUSTOMER') = (seq is not null)),
    constraint points_account_balance_check check (balance >= 0),
    constraint points_account_customer_key unique (customer_id)
);

create unique index points_account_system_key on points_account (kind) where customer_id is null;

insert into points_account (kind) values ('ISSUED'), ('REDEEMED');

-- One posting. Never updated or deleted: a mistake is undone by a refund or a new posting.
create table points_txn (
    id              uuid         primary key default uuidv7(),
    kind            varchar(16)  not null,
    customer_id     bigint       not null references customer (id),
    -- The caller's Idempotency-Key. A retry with the same key and request replays this transaction.
    idempotency_key varchar(128) not null,
    -- SHA-256 of the request, to tell a retry from a different request reusing the key.
    request_hash    bytea        not null,
    -- The caller's own code and id for the posting. Opaque to core.
    reason          varchar(32),
    reference       varchar(128),
    refund_of       uuid references points_txn (id),
    recorded_at     timestamptz  not null,
    -- The date in Bali (finns.points.time-zone), for reconciliation.
    business_date   date         not null,
    constraint points_txn_kind_check check (kind in ('CREDIT', 'DEBIT', 'REFUND')),
    constraint points_txn_refund_check check ((kind = 'REFUND') = (refund_of is not null)),
    constraint points_txn_idempotency_key unique (idempotency_key),
    constraint points_txn_refund_of_key unique (refund_of)
);

create index points_txn_business_date_idx on points_txn (business_date);

create table points_entry (
    id            bigint generated always as identity primary key,
    txn_id        uuid   not null references points_txn (id),
    account_id    bigint not null references points_account (id),
    amount        bigint not null,
    -- The customer account's balance and seq after this entry; null on system accounts.
    balance_after bigint,
    seq           bigint,
    constraint points_entry_amount_check check (amount <> 0),
    constraint points_entry_after_check check ((balance_after is null) = (seq is null))
);

create index points_entry_txn_idx on points_entry (txn_id);

-- Append-only. Doesn't block truncate, which only the tests use.
create function points_append_only() returns trigger language plpgsql as $$
begin
    raise exception '% is append-only', tg_table_name using errcode = 'restrict_violation';
end $$;

create trigger points_txn_append_only before update or delete on points_txn
    for each row execute function points_append_only();
create trigger points_entry_append_only before update or delete on points_entry
    for each row execute function points_append_only();

-- Each transaction's entries sum to zero, checked at commit, once all of them are in.
create function points_txn_balanced() returns trigger language plpgsql as $$
begin
    if (select sum(amount) from points_entry where txn_id = new.txn_id) <> 0 then
        raise exception 'points transaction % does not balance', new.txn_id using errcode = 'check_violation';
    end if;
    return null;
end $$;

create constraint trigger points_entry_balanced after insert on points_entry
    deferrable initially deferred for each row execute function points_txn_balanced();

-- The feed of new customers and postings. Served in (xid, id) order, and only from transactions older
-- than every transaction still running, so a reader never skips a row that commits late. No foreign
-- keys, like check_in: history outlives what it points at.
create table points_event (
    id          bigint      generated always as identity primary key,
    xid         xid8        not null default pg_current_xact_id(),
    type        varchar(32) not null,
    customer_id bigint      not null,
    txn_id      uuid,
    at          timestamptz not null,
    constraint points_event_type_check check (type in ('customer.created', 'points.posted')),
    constraint points_event_txn_check check ((type = 'points.posted') = (txn_id is not null))
);

create index points_event_feed_idx on points_event (xid, id);

-- Customers who exist already joined before the feed did.
insert into points_event (type, customer_id, at)
select 'customer.created', id, created_at from customer order by id;
