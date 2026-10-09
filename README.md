# core

The FINNS backend: Quarkus 3 (Java 25), Postgres, Flyway. Built as a native image and deployed to
DigitalOcean from GHCR (`.github/workflows/ci.yml`).

Today it serves the Admin Console's staff-access model, a proof of concept of QR self-check-in
(gates, rooms, lockers), and the points ledger behind the membership program run with Sota Platforms. Customer-facing apps use it too, so the domain word for console accounts is
**staff**, and for the people using those apps **customer**, never "user".

## Developing

Needs Docker, for Dev Services' Postgres.

```sh
./mvnw quarkus:dev        # http://localhost:8080, Dev UI at /q/dev/
./mvnw verify -DskipITs=false   # unit tests + packaged-app smoke tests (what PRs run)
```

- **Dev** runs `db/migration` plus the dev seed in `db/dev`: `thomas@nordeast.id` (ADMIN),
  `admin@bla.com` (ADMIN) and `staff@bla.com` (STAFF). The dev database is kept between restarts.
- **Tests** always start from an empty database, and each test resets the tables.
- The dev and test admin-API, gate-API, booking and points tokens in `application.properties` are public and dev-only.
- Dev Services runs Postgres 18, like production (the points ledger uses `uuidv7()`, new in 18).
- **Dev** checks customer tokens against the real test realm
  (`https://test-auth.finnsbeachclub.com/realms/finns`), so the `app` can sign in and call a local core.
  **Tests** sign their own with `src/test/resources/customer-token-key.pem`, a test-only key.

The dev database name is `finns`, so Dev Services never picks up a reused container from another
project.

## Staff access

A `staff` row is someone allowed into the Admin Console, with role `STAFF` or `ADMIN`. `ADMIN`
includes everything `STAFF` can do.

**Linking to JumpCloud**

- Admins add people by email.
- The first successful sign-in links that email's row to the person's JumpCloud subject (`sub`).
- From then on the row is found by `sub`, so a recreated JumpCloud account with the same email
  gets `sub_mismatch` and is refused until an admin unlinks the row.
- A disabled row is never linked, even if it's disabled while the sign-in is running.

**Rules** (enforced in `StaffApi`, under a transaction-scoped advisory lock taken by
`Staff.lockAccessChanges()` before anything is read):

- Only an active ADMIN, identified by the `X-Finns-Actor-Sub` header, can list or change staff.
- Nobody can change their own role, active flag or link, or delete themselves. They can edit their
  own name and email.
- At least one active ADMIN always remains.
- Writes need `If-Match: "<version>"` from the `ETag`: a missing header gets 428, a stale one 412.
- Every access change writes a `staff_event` row in the same transaction.

**Bootstrap:** at startup, while no active ADMIN exists, the emails in
`finns.staff.bootstrap-admins` become active ADMINs. It's a no-op otherwise. An invalid email fails
startup.

## Admin API

Everything under `/api/v1/admin/` needs `Authorization: Bearer <FINNS_ADMIN_API_TOKEN>`,
including unknown paths (401, empty body). The only caller is the Admin Console Worker, and the
token is compared in constant time. Errors are RFC 9457 `application/problem+json`, with a stable
`code` (see `ErrorCode`) and, for `invalid`, a `fields` map of field → error code. Clients own the
wording.

| Method and path | Purpose |
|---|---|
| `GET /api/v1/admin/staff-access/{sub}` | Per-request lookup: `{status: active\|disabled\|none, staffId?, role?}`. Read-only, and matches by linked `sub` only. |
| `POST /api/v1/admin/staff-access/sign-ins` | Called once per sign-in with `{sub, email}`. Matches by `sub`, else by email (linking it), and records `last_sign_in_at`. May also answer `sub_mismatch`. If this answers `active`, the lookup of that `sub` does too. |
| `GET /api/v1/admin/staff` | List: `q` (email/name substring), `role`, `active`, `page` (1-based), `size` (≤ 200). |
| `GET /api/v1/admin/staff/{id}` | One staff member, with `ETag`. |
| `GET /api/v1/admin/staff/{id}/events` | Audit events, newest first (`limit` ≤ 100). |
| `POST /api/v1/admin/staff` | Create: `{email, displayName?, role, active}`. |
| `PUT /api/v1/admin/staff/{id}` | Update (`If-Match`). |
| `DELETE /api/v1/admin/staff/{id}/link` | Unlink the JumpCloud subject (`If-Match`). |
| `DELETE /api/v1/admin/staff/{id}` | Delete (`If-Match`). The events are kept. |
| `GET /api/v1/admin/customers` | Customers with their points, newest first: `{items: [{customerId, displayName, email, deletedAt, balance, createdAt}], total, page, size}`. `customerId` is the public id; `displayName` and `email` are core's copy of the Keycloak account (see [Keycloak sync](#keycloak-sync)), null if it has none; `deletedAt` is when core found the account deleted, else null; `balance` is 0 before any posting. `q` is a whole customer id (that customer) or text to find in display names and emails, ignoring case. `page` (1-based), `size` (≤ 200). Any active staff member. |
| `GET /api/v1/admin/customers/{customerId}` | `{customerId, displayName, email, deletedAt, balance, seq, createdAt, transactions}`: the latest 100 transactions, newest first, each like the Points API's (without `customerId` and `seq`) plus `staffId` and `staffEmail` (null for the Points API's; `staffEmail` also once the staff member is deleted). Any active staff member. |
| `POST /api/v1/admin/customers/{customerId}/credits` | Staff adjustment: `{points, reference?}` with `Idempotency-Key` → `201` transaction. Any active staff member; see [Points](#points). |
| `POST /api/v1/admin/customers/{customerId}/debits` | Same, or `409 insufficient_points`. |

`/q/health/ready` (which includes the database) is public, for the platform's health check.

## Customers

A **customer** signs in to the customer-facing apps with Keycloak (realm `finns`, public client
`trident-app`). Keycloak owns their account and profile. Every user of the realm is a customer: core
keeps a `customer` row per Keycloak subject (`sub`), created when they sign up (see
[Keycloak sync](#keycloak-sync)), or on their first authenticated request if core hadn't heard yet, so
other rows can point at them. The row also keeps an exact copy of their display name and email, so staff
can see and search customers in the Admin Console.

Everything under `/api/v1/app/` needs `Authorization: Bearer <access token>`, including unknown
paths (401, empty body), checked before routing. Core accepts only access tokens that:

- are signed by the realm's current keys, from the issuer in `FINNS_AUTH_ISSUER`, and unexpired,
- were issued to the customer app (`azp` is `trident-app`), are access tokens (`typ` is `Bearer`),
  and carry a subject.

Every customer is also a points member (see [Points](#points)): there's no separate enrolment.
Points partners know a customer only by `customer.public_id`, a random UUID, never the internal id
or the Keycloak subject.

Keycloak isn't needed to start: if it's unreachable, core starts anyway and connects on the first
customer request (which fails until Keycloak is back).

## Keycloak sync

The customer's display name and email in core are an exact copy of their Keycloak account, updated
within about a second of any change:

- **core's Keycloak extension** (`keycloak-extension/`, installed in Keycloak; setup and updates in
  [`keycloak-extension/manual-keycloak.md`](keycloak-extension/manual-keycloak.md)) listens to realm
  `finns`. After a sign-up, sign-in, profile or email change, email verification, account deletion, or
  any admin change to a user commits, it posts `{userId}` to `POST /api/v1/keycloak/user-changes`
  (bearer `FINNS_KEYCLOAK_WEBHOOK_TOKEN`), asynchronously, retrying after 1, 5 and 30 seconds while
  core can't be reached or answers 5xx. Signing out changes nothing and sends nothing.
- **core reads the user itself** from Keycloak's Admin API as the service account of the confidential
  client `finns-core-sync` (realm-management role `view-users` only), so the notice carries no data. It
  answers `204` once the copy matches, `503 unavailable` if Keycloak couldn't be read (the extension
  retries), and creates the customer if they're new.
- The display name is built as Keycloak builds the `name` claim: first and last name, leaving out an
  empty one, joined by a space; null if both are empty. Values are copied exactly, never cut.
- A write lands only if its Keycloak fetch started later than the one already stored
  (`profile_fetched_at`), so notices handled out of order can't bring back an older copy.
- A deleted Keycloak user keeps their customer row (points and check-ins point at it) without a name or
  email, and with `keycloak_deleted_at`. A deleted user never becomes a customer, nor does a client's
  service account.
- **Reconciliation** (`finns.keycloak.reconcile-every`, 10 minutes, first run 1 minute after startup)
  reads every user of the realm, syncs each, creates missing customers, then checks one by one the
  customers it didn't see and marks those Keycloak no longer has. It copies whatever a lost notice
  missed, and filled in existing customers when this shipped.
- Only the Admin API returns the copy: never the Points or Gate APIs, the points feed or logs.
- Requests never write it: issuing QR or handoff codes and signing out leave it as it is. A handoff
  still takes the name and email for the booking website from the app's token.

| Method and path | Auth | Purpose |
|---|---|---|
| `POST /api/v1/keycloak/user-changes` | `Bearer <FINNS_KEYCLOAK_WEBHOOK_TOKEN>` | `{userId}`: the Keycloak user changed. `204`, `400 malformed`, or `503 unavailable`. |

## Booking website handoff

A customer signed in to the app shouldn't sign in again on the booking website (`booking`). The app
asks core for a one-time **handoff code**, opens the website with it, and the website redeems it with
core. A handoff signs the customer in to a *short, identify-only* website session: it is not a token,
and the website never receives an access token.

- A code is 32 random bytes (base64url, 43 characters). Only its SHA-256 is stored, with the customer,
  the app's Keycloak session (`sid`) and the customer's display name and email, which exist only so the
  website can say who it is about to sign in. Redeeming or revoking a code clears the name and email, and
  so does a job every minute once it has expired (`HandoffPurge`).
- It can be redeemed once, within `finns.booking.handoff-ttl` (60 s). Redemption is one conditional
  update, so two requests can't both win. Anything else (unknown, malformed, used, expired, revoked) is
  the same `404 not_found`.
- A customer can have 5 codes issued per minute (`429 rate_limited`), counted under a row lock so
  concurrent requests can't slip under the limit. Codes expired for an hour are deleted.
- Signing out of the app calls `POST /api/v1/app/logout`: core revokes that Keycloak session's unused
  codes and tells the website (`POST <FINNS_BOOKING_URL>/auth/revoke` with `{sub, sid}`, bearer
  `FINNS_BOOKING_REVOKE_TOKEN`, one retry) to sign the customer out of it everywhere. The `sub` and `sid`
  come from the caller's own token, never from the request; `sid` is left out if the token has none. If
  the website can't be reached, its sessions expire on their own (15 minutes on the website's side).
- Core never accepts cookies, and the website's own session is not core's concern.

| Method and path | Auth | Purpose |
|---|---|---|
| `POST /api/v1/app/handoffs` | `Bearer <customer access token>` | Issue a code: `201 {code, ttlSeconds}`. |
| `POST /api/v1/app/logout` | `Bearer <customer access token>` | The customer signed out of the app: `204`. |
| `POST /api/v1/booking/handoffs/peek` | `Bearer <FINNS_BOOKING_API_TOKEN>` | `{code}` → `200 {displayName, email}` without using the code, for the website's confirmation page. |
| `POST /api/v1/booking/handoffs/redeem` | `Bearer <FINNS_BOOKING_API_TOKEN>` | `{code}` → `200 {sub, sid, displayName, email}`, once. |

## Check-in (proof of concept)

A signed-in customer gets a one-time QR code in the customer app (`app`); a gate device (`gate`, a
separate Android app) scans it and asks core whether to open.

- A QR code is 32 random bytes, shown as `FINNS1:<base64url>`. Only its SHA-256 is stored, with the
  customer it was issued to.
- It can be used once, and only for `finns.qr.ttl` (60 s) after it's issued. The app refreshes it
  before it expires.
- Every scan is recorded in `check_in`, with the customer whose QR code it was (none if the QR code
  is unknown or malformed).
- **There are no per-gate rules yet:** any customer's valid QR code opens any gate. Nothing that
  controls a real door may rely on this until they exist.

| Method and path | Auth | Purpose |
|---|---|---|
| `POST /api/v1/app/qrs` | `Bearer <customer access token>` | Issue a QR code to the customer: `201 {qr, expiresAt, ttlSeconds}`. Render `qr` verbatim; count down `ttlSeconds` on the device's clock. |
| `POST /api/v1/gate/check-ins` | `Bearer <FINNS_GATE_API_TOKEN>` | `{gateId, qr}` → `200 {result: granted}` or `200 {result: denied, reason: expired\|used\|unknown\|malformed}`. `gateId` is `[a-z0-9-]{1,64}`; a bad `gateId` or missing field is 400 `malformed`. |

The prefix versions the QR format, so a signed QR code that gates can verify offline can be added
later as `FINNS2:` without breaking deployed gates.

Try it against `./mvnw quarkus:dev`. The customer token has to come from the app, because
`trident-app` only has the browser sign-in flow: run the app's web build against this core (see the
`app` README), sign in, and copy the `Authorization` header of its `qrs` request from the browser's
developer tools.

```sh
CUSTOMER_TOKEN=...   # from the app, valid for 5 minutes
QR=$(curl -s -X POST localhost:8080/api/v1/app/qrs -H "Authorization: Bearer $CUSTOMER_TOKEN" | jq -r .qr)
curl -s localhost:8080/api/v1/gate/check-ins -H 'Content-Type: application/json' \
  -H 'Authorization: Bearer dev-only-gate-token-not-a-secret-01234' \
  -d "{\"gateId\":\"dev-gate\",\"qr\":\"$QR\"}"
```

## Points

The membership program is split between core and a points partner, Sota Platforms. **core decides
the "who"**: it keeps the member list (every customer) and each one's points, and is their system of
record. **Sota decides the "how"**: raffles, rewards, redemptions and campaigns, adding and deducting
points through the Points API. Sota keeps the record of its own draws, entries and prizes. core
never posts points on its own; besides Sota, only staff adjust a balance, by hand in the Admin Console
(see [Staff adjustments](#staff-adjustments)). There are no tiers, expiry or holds.

**Ledger.** Double-entry, in whole points (`bigint`). Each transaction (`points_txn`: `CREDIT`,
`DEBIT` or `REFUND`) has two entries (`points_entry`) that sum to zero: one on the customer's account
(`points_account`, created by their first posting) and one on a system account (`ISSUED` for credits,
`REDEEMED` for debits). Postgres enforces that:

- a transaction's entries sum to zero (deferred constraint trigger, at commit),
- a customer's balance is never negative,
- transactions and entries are never updated or deleted (trigger),
- an idempotency key is used once per client, and a transaction is refunded at most once (unique
  indexes).

Only customer accounts cache their `balance` and `seq` (postings so far): caching the system
accounts' would queue every posting on one row. A **daily check** (`PointsCheck`, 02:30 in Bali,
`finns.points.check-cron`) compares each customer account's cache with its entries and that all entries
sum to zero, logging `points.check_failed` at ERROR on any mismatch. One instance runs it, under an
advisory lock.

**Posting** (`PointsTxn.post`/`postByStaff`/`refund`, the only writers) is one Postgres transaction: claim the
idempotency key (`insert ... on conflict do nothing`, which waits for a concurrent request with the
same key), move the balance with one conditional update (`balance + delta >= 0`, which is also the
customer's lock, so one customer's postings queue and different customers' don't wait for each
other), insert the entries, append a feed event. A failure rolls everything back, including the key,
so only successful postings replay.

**Refunds** reverse a credit or debit in full, once. A refund of a credit whose points were spent
fails with `insufficient_points`. A refund can't be refunded; correct it with a new posting. Neither
can a staff adjustment (`not_refundable`): staff correct theirs with an opposite adjustment.

**Staff adjustments.** Any active staff member can credit or debit a customer in the Admin Console
(`POST /api/v1/admin/customers/{customerId}/credits|debits`). They're ordinary postings with the same
checks, recorded with the staff member (`points_txn.staff_id`) and the reason `staff_adjustment`, so
Sota sees them in the feed and the reconciliation like its own. Idempotency keys are scoped per client
(Sota's, and each staff member's), so one can't replay or block another's posting.

**Feed.** `points_event` lists new customers (`customer.created`, written by `Customer.ofSubject`) and
postings (`points.posted`), in the transaction that made them. It's served in `(xid, id)` order (`xid`
is the writer's transaction id) and only from transactions older than every one still running, so
a reader never skips an event that commits late. A long-running write transaction anywhere on the
cluster (Keycloak's database shares it) delays the feed until it ends, without losing events.
`points.posted` carries the account's `seq` and `balance` after the posting: feed order isn't always
one customer's posting order, so consumers keep the highest `seq`. Events are kept forever, so
reading from the start rebuilds a copy.

**Business dates** are Bali's (`finns.points.time-zone`, `Asia/Makassar`).

Everything under `/api/v1/points/` needs `Authorization: Bearer <FINNS_POINTS_API_TOKEN>`, including
unknown paths (401, empty body), checked before routing like the other token APIs. It's called server
to server. Responses are `Cache-Control: no-store`.

| Method and path | Purpose |
|---|---|
| `GET /api/v1/points/customers/{customerId}` | `{customerId, balance, seq}`; 0 and 0 before any posting. 404 if unknown. |
| `POST /api/v1/points/credits` | `{customerId, points, reason?, reference?}` → `201` transaction. |
| `POST /api/v1/points/debits` | Same → `201`, or `409 insufficient_points`. |
| `POST /api/v1/points/transactions/{transactionId}/refund` | `{reference?}` (body optional) → `201` transaction. `409 already_refunded`, `not_refundable` or `insufficient_points`; 404 if unknown. |
| `GET /api/v1/points/events?after=&limit=` | `{events: [{eventId, type, customerId, at, transaction?}], next}`. Omit `after` to read from the start, then pass `next` back. `limit` 1–500 (default 100). |
| `GET /api/v1/points/reconciliation/{businessDate}` | `{businessDate, timeZone, credits, debits, refunds, transactionsSha256}`: each total is `{count, points}` (net effect on balances), and the hash is SHA-256 (hex) of the date's sorted lowercase transaction ids, each followed by `\n`. Final once the date has ended in Bali. |

- The three `POST`s need `Idempotency-Key` (1–128 printable ASCII characters, no spaces; missing or
  bad is `400 malformed`). Keys are Sota's own (staff postings have their own) and never expire. The same key and
  request returns the original `201` and body with `Idempotent-Replayed: true`; the same key with a
  different request is `422 idempotency_mismatch`. Callers retry timeouts, 5xx and 429 with the same
  key until they get an answer.
- A transaction is `{transactionId, kind (credit|debit|refund), customerId, points, balance, seq,
  reason?, reference?, refundOf?, recordedAt}`. `points` is signed, from the customer's side; `balance`
  and `seq` are the account's after it.
- `points` is a JSON integer, 1 to 1,000,000,000 (a fraction or a string is `invalid`). `reason` is
  the caller's code (`[A-Za-z0-9_.:-]{1,32}`) and `reference` its id (like the key); both are opaque to
  core. Field errors are `422 invalid` with `fields`. `customerId` takes the standard UUID form in
  either case; responses use lowercase.

## Configuration

| Env var | Meaning |
|---|---|
| `QUARKUS_DATASOURCE_JDBC_URL` | For example `jdbc:postgresql://<host>:25060/finns?sslmode=require` (DO Managed Postgres) |
| `QUARKUS_DATASOURCE_USERNAME`, `QUARKUS_DATASOURCE_PASSWORD` | Database credentials |
| `FINNS_ADMIN_API_TOKEN` | At least 32 characters (`openssl rand -base64 32`). Must equal the Worker's `CORE_API_TOKEN` secret. Startup fails if it's missing or short. |
| `FINNS_GATE_API_TOKEN` | At least 32 characters. The gate devices' bearer token for `/api/v1/gate/*`; use a different value from `FINNS_ADMIN_API_TOKEN`. Startup fails if it's missing or short. |
| `FINNS_AUTH_ISSUER` | The Keycloak realm customers sign in to, for example `https://test-auth.finnsbeachclub.com/realms/finns` (exactly the tokens' `iss`). Startup fails if it's missing. |
| `FINNS_BOOKING_API_TOKEN` | At least 32 characters. The booking website's bearer token for `/api/v1/booking/*`; different from the other tokens. Must equal the website's `CORE_API_TOKEN` secret. Startup fails if it's missing or short. |
| `FINNS_BOOKING_REVOKE_TOKEN` | At least 32 characters. What core sends the booking website when a customer signs out of the app; different from every other token. Must equal the website's `REVOKE_TOKEN` secret. Startup fails if it's missing or short. |
| `FINNS_POINTS_API_TOKEN` | At least 32 characters. Points partners' (Sota's) bearer token for `/api/v1/points/*`; different from every other token. Startup fails if it's missing or short. |
| `FINNS_BOOKING_URL` | The booking website's origin, for example `https://trident-poc-booking.juna.workers.dev`. Startup fails if it's missing, or if it isn't https (plain http is allowed for `localhost` only). |
| `FINNS_KEYCLOAK_WEBHOOK_TOKEN` | At least 32 characters, different from every other token. What core's Keycloak extension sends on `/api/v1/keycloak/*`; must equal the extension's `token`. Startup fails if it's missing or short. |
| `FINNS_KEYCLOAK_CLIENT_SECRET` | The secret of Keycloak client `finns-core-sync` (realm `finns`, service account with `view-users`), which core reads users as. The issuer is `FINNS_AUTH_ISSUER`'s. Startup fails if it's missing. |
| `FINNS_KEYCLOAK_RECONCILE_EVERY` | Optional. How often reconciliation runs, as a duration (`10m` by default) or `off`. In dev it's `off` unless set. |
| `FINNS_CORS_ORIGINS` | Optional. Browser origins allowed to call core, as a Quarkus CORS origin list (`/regex/` entries allowed). Defaults to the app's `trident-app-web` Worker and its preview aliases on `workers.dev`. |
| `FINNS_STAFF_BOOTSTRAP_ADMINS` | Overrides the default bootstrap admin, `thomas@nordeast.id`. Comma-separated. |

Flyway migrates at startup, and Hibernate only validates the schema against the entities.

**Rotating the token:** set the new value here, then as the Worker's secret, as close together as
possible. The console returns 503 in between.

## Deploying

A push to `main` builds the native image (running the smoke tests against it) and pushes
`ghcr.io/<repo>:latest` and `:<sha>`. Pull requests run the JVM tests only.
