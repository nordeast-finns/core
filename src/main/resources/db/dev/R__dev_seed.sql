-- Dev only (see %dev.quarkus.flyway.locations). The bla.com rows are fixtures for the list screen.
insert into staff (email, display_name, role, created_at, created_by, updated_at, updated_by)
values ('thomas@nordeast.id', 'Thomas', 'ADMIN', now(), 'system', now(), 'system'),
       ('admin@bla.com', 'Admin Example', 'ADMIN', now(), 'system', now(), 'system'),
       ('staff@bla.com', 'Staff Example', 'STAFF', now(), 'system', now(), 'system')
on conflict (email) do nothing;
