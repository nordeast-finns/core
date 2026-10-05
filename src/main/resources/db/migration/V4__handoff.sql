-- One-time codes that hand a signed-in customer from the customer app to the booking website. Only the
-- code's hash is stored. display_name and email exist only so booking can greet the customer, and are
-- cleared as soon as the code is redeemed or revoked.
create table handoff (
    id           uuid         primary key,
    customer_id  bigint       not null references customer (id),
    code_hash    bytea        not null,
    -- The customer app's Keycloak session, so signing out of the app can revoke the booking sessions
    -- it started. Null when the access token carried no sid.
    keycloak_sid varchar(128),
    display_name varchar(128),
    email        varchar(320),
    issued_at    timestamptz  not null,
    expires_at   timestamptz  not null,
    used_at      timestamptz,
    constraint handoff_code_hash_key unique (code_hash)
);

create index handoff_customer_idx on handoff (customer_id, issued_at desc);
create index handoff_sid_idx on handoff (keycloak_sid) where keycloak_sid is not null;
create index handoff_expires_idx on handoff (expires_at);
