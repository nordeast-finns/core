-- Staff can credit and debit a customer's points in the Admin Console. Each posting records the staff
-- member who made it; null for the Points API's. No foreign key, like check_in: history outlives the
-- staff row.
alter table points_txn add column staff_id bigint;

-- Idempotency keys are scoped per client: the Points API's (staff_id null) and each staff member's
-- are separate, so one client can't replay or block another's posting.
alter table points_txn drop constraint points_txn_idempotency_key;
alter table points_txn add constraint points_txn_idempotency_key unique nulls not distinct (staff_id, idempotency_key);

-- A customer's history in the Admin Console.
create index points_txn_customer_idx on points_txn (customer_id);
