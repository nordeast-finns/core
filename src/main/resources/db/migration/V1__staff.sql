-- People allowed into the Admin Console, and their role. Matched by email until their first
-- sign-in links the JumpCloud subject; the subject is authoritative from then on.
create table staff (
    id              bigint generated always as identity primary key,
    email           varchar(254) not null,
    display_name    varchar(200),
    role            varchar(16)  not null,
    active          boolean      not null default true,
    jumpcloud_sub   varchar(128),
    last_sign_in_at timestamptz,
    created_at      timestamptz  not null,
    created_by      varchar(254) not null,
    updated_at      timestamptz  not null,
    updated_by      varchar(254) not null,
    version         integer      not null default 0,
    constraint staff_role_check check (role in ('STAFF', 'ADMIN')),
    -- Emails are printable ASCII and lowercase, so Java, JavaScript and Postgres agree on them.
    constraint staff_email_norm check (email = lower(email) and email !~ '[^\x21-\x7e]'),
    constraint staff_email_key unique (email),
    constraint staff_sub_key unique (jumpcloud_sub)
);

-- Audit trail of access changes. No foreign key: history outlives deleted staff.
create table staff_event (
    id          bigint generated always as identity primary key,
    staff_id    bigint       not null,
    staff_email varchar(254) not null,
    action      varchar(16)  not null,
    changes     jsonb,
    -- Null for system events (bootstrap, linking at sign-in).
    actor_id    bigint,
    actor_email varchar(254) not null,
    at          timestamptz  not null,
    constraint staff_event_action_check
        check (action in ('CREATED', 'UPDATED', 'DELETED', 'UNLINKED', 'LINKED', 'BOOTSTRAPPED'))
);

create index staff_event_staff_idx on staff_event (staff_id, at desc);
