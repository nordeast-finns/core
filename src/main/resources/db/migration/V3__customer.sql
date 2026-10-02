-- A customer of the customer-facing apps, known by their Keycloak subject (realm `finns`). Created on
-- their first authenticated request. Keycloak owns their profile, so nothing else is copied here.
create table customer (
    id           bigint generated always as identity primary key,
    keycloak_sub varchar(128) not null,
    created_at   timestamptz  not null,
    constraint customer_keycloak_sub_key unique (keycloak_sub)
);

-- Every QR code is now issued to a signed-in customer. The anonymous ones issued before this
-- expired 60 s after they were issued, so they're dropped rather than kept without an owner.
delete from qr;
alter table qr add column customer_id bigint not null references customer (id);
create index qr_customer_idx on qr (customer_id);

-- The customer the scanned QR code was issued to; null when the scan matched no QR code. No foreign
-- key, like qr_id: check-in history outlives the rows it points at.
alter table check_in add column customer_id bigint;
create index check_in_customer_idx on check_in (customer_id, scanned_at desc);
