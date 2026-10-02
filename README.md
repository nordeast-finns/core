# core

The FINNS backend: Quarkus 3 (Java 25), Postgres, Flyway. Built as a native image and deployed to
DigitalOcean from GHCR (`.github/workflows/ci.yml`).

Today it serves the Admin Console's staff-access model and a proof of concept of QR self-check-in
(gates, rooms, lockers). Customer-facing apps use it too, so the domain word for console accounts is
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
- The dev and test admin-API and gate-API tokens in `application.properties` are public and dev-only.
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

`/q/health/ready` (which includes the database) is public, for the platform's health check.

## Customers

A **customer** signs in to the customer-facing apps with Keycloak (realm `finns`, public client
`trident-app`). Keycloak owns their account and profile; core keeps a `customer` row per Keycloak
subject (`sub`), created on their first authenticated request, so other rows can point at them.

Everything under `/api/v1/app/` needs `Authorization: Bearer <access token>`, including unknown
paths (401, empty body), checked before routing. Core accepts only access tokens that:

- are signed by the realm's current keys, from the issuer in `FINNS_AUTH_ISSUER`, and unexpired,
- were issued to the customer app (`azp` is `trident-app`), are access tokens (`typ` is `Bearer`),
  and carry a subject.

Keycloak isn't needed to start: if it's unreachable, core starts anyway and connects on the first
customer request (which fails until Keycloak is back).

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

## Configuration

| Env var | Meaning |
|---|---|
| `QUARKUS_DATASOURCE_JDBC_URL` | For example `jdbc:postgresql://<host>:25060/finns?sslmode=require` (DO Managed Postgres) |
| `QUARKUS_DATASOURCE_USERNAME`, `QUARKUS_DATASOURCE_PASSWORD` | Database credentials |
| `FINNS_ADMIN_API_TOKEN` | At least 32 characters (`openssl rand -base64 32`). Must equal the Worker's `CORE_API_TOKEN` secret. Startup fails if it's missing or short. |
| `FINNS_GATE_API_TOKEN` | At least 32 characters. The gate devices' bearer token for `/api/v1/gate/*`; use a different value from `FINNS_ADMIN_API_TOKEN`. Startup fails if it's missing or short. |
| `FINNS_AUTH_ISSUER` | The Keycloak realm customers sign in to, for example `https://test-auth.finnsbeachclub.com/realms/finns` (exactly the tokens' `iss`). Startup fails if it's missing. |
| `FINNS_CORS_ORIGINS` | Optional. Browser origins allowed to call core, as a Quarkus CORS origin list (`/regex/` entries allowed). Defaults to the app's `trident-app-web` Worker and its preview aliases on `workers.dev`. |
| `FINNS_STAFF_BOOTSTRAP_ADMINS` | Overrides the default bootstrap admin, `thomas@nordeast.id`. Comma-separated. |

Flyway migrates at startup, and Hibernate only validates the schema against the entities.

**Rotating the token:** set the new value here, then as the Worker's secret, as close together as
possible. The console returns 503 in between.

## Deploying

A push to `main` builds the native image (running the smoke tests against it) and pushes
`ghcr.io/<repo>:latest` and `:<sha>`. Pull requests run the JVM tests only.
