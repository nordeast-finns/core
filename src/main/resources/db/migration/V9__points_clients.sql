-- core now credits points itself, for paid bookings and check-ins, so a posting records which client
-- made it: the Points API's partner, a staff member, or core. Only the partner's postings can be
-- refunded through the Points API.
alter table points_txn add column client varchar(16) not null default 'PARTNER';
alter table points_txn alter column client drop default;

-- Existing staff postings. points_txn is append-only, so its trigger is off for this one update only:
-- the migration runs in one transaction, and the new column is all it changes.
alter table points_txn disable trigger points_txn_append_only;
update points_txn set client = 'STAFF' where staff_id is not null;
alter table points_txn enable trigger points_txn_append_only;

alter table points_txn add constraint points_txn_client_check check (
    client in ('PARTNER', 'STAFF', 'CORE') and (client = 'STAFF') = (staff_id is not null));

-- Idempotency keys are scoped per client: the partner's, core's and each staff member's are separate.
alter table points_txn drop constraint points_txn_idempotency_key;
alter table points_txn add constraint points_txn_idempotency_key unique nulls not distinct (client, staff_id, idempotency_key);
