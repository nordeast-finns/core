# core

See README.md for running, configuration and the Admin API.

## Conventions

- **Package by function**: `com.finns.trident.core.model` (Panache entities, their finders and the rules
  they enforce), `com.finns.trident.core.api` (JAX-RS resources named `XxxApi`, with their request and
  response records nested inside), and root-level cross-cutting classes (`ErrorCode`,
  `BusinessException`, `FinnsConfig`, startup jobs). Add sub-packages only once a boundary is
  clear.
- **No service layer by default.** An `XxxApi` validates input and works with the model directly.
  Add a service only when logic is shared by several callers and can't sit on an entity.
- **Never use the word "user".** `core` serves both the Admin Console and customer-facing apps, so
  "user" is ambiguous. Admin Console accounts are **staff** (an admin is a staff member with role
  `ADMIN`); people using the customer-facing apps are **customers**; the person making a request is
  the **actor**.
- **Formatting:** tabs, and braces on the same line.
- **Errors:** throw `BusinessException(ErrorCode.X)`. `GenericExceptionMapper` turns it into
  `application/problem+json`. Add new codes to `ErrorCode` with their HTTP status. Never put
  user-facing wording in `core`: clients map codes to text.
- **Schema:** Flyway migrations in `src/main/resources/db/migration`. Never edit a migration that
  has been merged; add a new one. Hibernate runs with `schema-management.strategy=validate`.
- **Native image:** records used as JSON bodies but not returned directly by a resource method
  need `@RegisterForReflection`.

## Admin API rules

- Everything under `/api/v1/admin/` is authenticated by `AdminApiFilter` (the Worker's bearer
  token) before routing. New admin endpoints go under that prefix and are protected by default.
- Endpoints that act for a staff member resolve the actor from `X-Finns-Actor-Sub` and require an
  active ADMIN in `core` itself. Never trust the Worker's role decision alone.
- Staff access changes take `Staff.lockAccessChanges()` **before** reading the actor or target, so
  invariant checks see every earlier change. Record a `StaffEvent` in the same transaction.
- Writes that sign-in makes (`Staff.link`, `Staff.touchSignIn`) are bulk updates that must not
  bump `version`. `Staff` is `@DynamicUpdate`, so an admin's edit never overwrites them. `link`
  is conditional (active, unlinked, subject not linked elsewhere); on a miss, sign-in re-reads
  once instead of taking the lock.
- Never log the token, request headers or bodies.

## Customer API rules

- Everything under `/api/v1/app/` is the customer app's API. Quarkus OIDC checks the customer's
  Keycloak access token before routing (`quarkus.http.auth.permission.app`), so new endpoints there
  are protected by default. Auth isn't proactive, so the admin and gate tokens never reach OIDC;
  keep it that way.
- Identify the customer by the token's subject only, through `Customer.ofSubject`. Never by email
  or name: Keycloak owns those, and core doesn't store them.
- The one place core keeps a customer's display name or email is a `handoff` row, and only until the
  code is redeemed or revoked (at most `finns.booking.handoff-ttl`, then cleared). Don't copy them
  anywhere else.
- Tests sign customer tokens with `Fixtures.customer(sub)` / `Fixtures.customerToken` (test-only key
  in `src/test/resources`). Never add a production key or real token to the repo.

## Booking API and handoff rules

- Everything under `/api/v1/booking/` is authenticated by `BookingApiFilter` (the booking website's
  bearer token) before routing, like the admin and gate APIs. It is called server to server only, so it
  has no CORS.
- A handoff code is a credential that signs a customer in to the booking website. Store only its hash,
  never log it, and keep it single-use (`Handoff.redeem` is one conditional update; keep it that way
  rather than read-then-write), short-lived, and rate-limited per customer under the customer's row lock.
- `Handoff.redeem` and `peek` return nothing for every kind of bad code, so callers can't tell unknown
  from used or expired. Keep it that way.
- Take the `sid` for logout from the caller's verified token, never from the request. Notifying the
  booking website (`BookingNotifier`) is best effort and must never delay or fail the app's sign-out.
- Core never accepts cookies and never hands the booking website an access token.

## Gate API and check-in rules

- Everything under `/api/v1/gate/` is authenticated by `GateApiFilter` (the gate devices' bearer
  token, one shared token for now) before routing.
- A QR code is issued to one customer, and every `check_in` row records that customer (null when
  the scan matched no QR code).
- A QR code's text is versioned by prefix (`Qr.V1_PREFIX`, `FINNS1:`). Never change what an
  existing prefix means: add a new prefix (a signed `FINNS2:` QR code a gate can verify offline is the
  expected next one) so deployed gates and apps keep working. Clients treat the text as opaque.
- Only a QR code's token hash is stored. Never log `qr`, tokens or scan bodies.
- `Qr.consume` is one conditional update, so concurrent scans of one QR code can't both be granted.
  Keep it that way rather than read-then-write.
- A denied scan is a normal outcome: 200 with `result`/`reason`, and a `check_in` row. Only a
  malformed request is a problem response. Every scan is recorded, granted or not, in the same
  transaction as the decision.
- `check_in.source`, `scanned_at` and `recorded_at` exist for check-ins that gates decide offline and
  upload later; online check-ins set `ONLINE` and equal times.

## Points API and ledger rules

- Everything under `/api/v1/points/` is authenticated by `PointsApiFilter` (the points partners'
  bearer token, Sota's for now) before routing, like the admin, gate and booking APIs. Server to server,
  no CORS.
- Every customer is a points member; there's no member table or enrolment. Partners know a customer
  only by `Customer.publicId`. Never expose the internal id or the Keycloak subject on this API, and
  never put personal data in the ledger or the feed.
- core decides the "who" (members and balances); the partner decides the "how" (what points are for).
  core provides the API but doesn't post points itself.
- `PointsTxn.post` and `refund` are the only writers of the ledger. Keep the ledger double-entry
  (two entries summing to zero), append-only (`points_txn`, `points_entry`), and the balance change one
  conditional update (`balance + delta >= 0`) rather than read-then-write. Postgres enforces these;
  don't add a path around them, and correct mistakes with new postings.
- Cache balances on customer accounts only, never on system accounts (one hot row for every posting).
- Every posting needs an `Idempotency-Key`. Only successful postings are stored and replayed; a failure
  rolls back the key too. Keys are global while there's one client; scope them per client before
  adding a second.
- Write a `points_event` in the same transaction as every new customer and posting. The feed is served
  in `(xid, id)` order below the snapshot's `xmin`, so it never skips a late commit. Keep it that way
  rather than paging by id or time.
- Points stay non-cash: no buying, transferring or cashing out points, and no money accounts, which
  would raise Indonesian e-money questions.

## Tests

`@QuarkusTest` with Dev Services Postgres 18, like production (Docker is required). `Fixtures` holds
the tokens, request builders and row helpers, and tests reset the tables in `@BeforeEach` (the
append-only points tables with `truncate`). `*IT` classes are HTTP-only smoke tests that CI runs
against the native image.
