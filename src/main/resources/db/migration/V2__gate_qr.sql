-- One-time check-in QR codes, shown in the customer app and scanned at a gate. Only the
-- token's hash is stored, so a database leak can't be replayed at a gate.
create table qr (
    id         uuid        primary key,
    token_hash bytea       not null,
    issued_at  timestamptz not null,
    expires_at timestamptz not null,
    used_at    timestamptz,
    used_gate  varchar(64),
    constraint qr_token_hash_key unique (token_hash)
);

create index qr_expires_idx on qr (expires_at);

-- Every scan a gate reports, granted or denied. No foreign key: history outlives purged QR codes.
create table check_in (
    id          bigint generated always as identity primary key,
    qr_id     uuid,
    gate_id     varchar(64) not null,
    result      varchar(16) not null,
    reason      varchar(16),
    -- ONLINE: core decided. OFFLINE: reserved for check-ins a gate decided alone and uploaded later.
    source      varchar(16) not null default 'ONLINE',
    -- scanned_at is the gate's clock, recorded_at core's; equal for ONLINE.
    scanned_at  timestamptz not null,
    recorded_at timestamptz not null,
    constraint check_in_result_check check (result in ('GRANTED', 'DENIED')),
    constraint check_in_reason_check check (reason in ('EXPIRED', 'USED', 'UNKNOWN', 'MALFORMED')),
    constraint check_in_source_check check (source in ('ONLINE', 'OFFLINE'))
);

create index check_in_gate_idx on check_in (gate_id, scanned_at desc);
