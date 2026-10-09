-- The customer's display name and email are now copied from Keycloak itself rather than from their access
-- token: Keycloak tells core a user changed (sign-up, sign-in, profile or email change, an admin's edit,
-- deletion) and core fetches the user from Keycloak's Admin API, plus a periodic reconciliation for any
-- notice that was lost. Keycloak still owns them; core never edits them.

-- Keycloak's name claim is the first and last name (up to 255 characters each) joined by a space, so it
-- needs more room than the token-era copy had. Copied whole, never cut.
-- (email, at most 255 in Keycloak, already fits.)
alter table customer alter column display_name type varchar(511);

-- When core started the Keycloak fetch the copy came from. A write only lands if its fetch started later
-- than the one already stored, so notices and reconciliation arriving out of order can't bring back an
-- older name or email: every change in Keycloak causes a fetch that starts after it.
alter table customer add column profile_fetched_at timestamptz;

-- When core found the Keycloak user gone. The row stays (points and check-ins point at it), without the
-- name and email.
alter table customer add column keycloak_deleted_at timestamptz;
