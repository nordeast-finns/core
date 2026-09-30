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
  `ADMIN`); the person making a request is the **actor**.
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

## Gate API and check-in rules

- Everything under `/api/v1/gate/` is authenticated by `GateApiFilter` (the gate devices' bearer
  token, one shared token for now) before routing. `/api/v1/app/` is the customer app's API and is
  unauthenticated for now.
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

## Tests

`@QuarkusTest` with Dev Services Postgres (Docker is required). `Fixtures` holds the token,
request builders and row helpers, and tests reset the tables in `@BeforeEach`. `*IT` classes are
HTTP-only smoke tests that CI runs against the native image.
