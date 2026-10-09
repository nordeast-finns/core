-- The customer's display name and email, so staff can see and search customers in the Admin Console. A
-- cache: Keycloak owns them, and core copies them from the customer's verified access token on each
-- request that resolves the customer, so they're as fresh as the customer's last use of the app. Null
-- when the token had no such claim, and until the customer's first request after this migration. Shown
-- only on the Admin API: never on the Points or Gate APIs, in the points feed or in logs.
alter table customer add column display_name varchar(128);
alter table customer add column email varchar(320);
