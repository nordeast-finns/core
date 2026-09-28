# core

The FINNS backend: Quarkus 3 (Java 25), Postgres, Flyway. Built as a native image and deployed to
DigitalOcean from GHCR (`.github/workflows/ci.yml`).

Today it serves the Admin Console's staff-access model. Customer-facing apps will use it too, so
the domain word for console accounts is **staff**, never "user".

## Developing

Needs Docker, for Dev Services' Postgres.

```sh
./mvnw quarkus:dev        # http://localhost:8080, Dev UI at /q/dev/
./mvnw verify -DskipITs=false   # unit tests + packaged-app smoke tests (what PRs run)
```

- **Dev** runs `db/migration` plus the dev seed in `db/dev`: `thomas@nordeast.id` (ADMIN),
  `admin@bla.com` (ADMIN) and `staff@bla.com` (STAFF). The dev database is kept between restarts.
- **Tests** always start from an empty database, and each test resets the tables.
- The dev and test admin-API tokens in `application.properties` are public and dev-only.

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

## Configuration

| Env var | Meaning |
|---|---|
| `QUARKUS_DATASOURCE_JDBC_URL` | For example `jdbc:postgresql://<host>:25060/finns?sslmode=require` (DO Managed Postgres) |
| `QUARKUS_DATASOURCE_USERNAME`, `QUARKUS_DATASOURCE_PASSWORD` | Database credentials |
| `FINNS_ADMIN_API_TOKEN` | At least 32 characters (`openssl rand -base64 32`). Must equal the Worker's `CORE_API_TOKEN` secret. Startup fails if it's missing or short. |
| `FINNS_STAFF_BOOTSTRAP_ADMINS` | Overrides the default bootstrap admin, `thomas@nordeast.id`. Comma-separated. |

Flyway migrates at startup, and Hibernate only validates the schema against the entities.

**Rotating the token:** set the new value here, then as the Worker's secret, as close together as
possible. The console returns 503 in between.

## Deploying

A push to `main` builds the native image (running the smoke tests against it) and pushes
`ghcr.io/<repo>:latest` and `:<sha>`. Pull requests run the JVM tests only.
