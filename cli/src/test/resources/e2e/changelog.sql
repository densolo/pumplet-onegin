--liquibase formatted sql

-- The e2e schema, as Liquibase would create it on both sides. Changesets without a
-- context run on both databases; "source" ones add source-only columns/tables and the
-- data, "target" ones make the target differ the way real schemas drift.

--changeset e2e:schema
create schema shop;

--changeset e2e:tables
-- Cyclic foreign keys: dept.manager_id -> emp, emp.dept_id -> dept.
create table shop.dept (id int primary key, name text not null, manager_id int);
create table shop.emp (id int primary key, dept_id int references shop.dept (id), name text not null, title varchar(20));
alter table shop.dept add constraint dept_manager_fkey foreign key (manager_id) references shop.emp (id);
create index emp_dept_idx on shop.emp (dept_id);
-- Generated column (computed on both sides).
create table shop.product (id int primary key, price numeric(10,2) not null, qty int not null,
    total numeric generated always as (price * qty) stored);
-- Identity and sequence columns.
create table shop.ticket (id int generated always as identity primary key, subject text);
create table shop.note (id bigserial primary key, body text);
-- Composite text key, and a uuid key.
create table shop.ledger (account text, entry_no int, amount numeric(14,2), primary key (account, entry_no));
create table shop.visit (id uuid primary key, at timestamptz not null);
-- No primary key.
create table shop.event_log (at timestamptz, msg text);
-- Large objects.
create table shop.document (id int primary key, body oid);
-- Big enough to be chunked.
create table shop.measurement (id bigint primary key, sensor int not null, value double precision, taken_at timestamptz not null);
create index measurement_sensor_idx on shop.measurement (sensor);
-- Its type differs on the target (see e2e:target-drift).
create table shop.mismatch (id int primary key, v text);

--changeset e2e:trigger splitStatements:false
-- Changes data, so a trigger left enabled during the load would show up in the checksums.
create function shop.touch_note() returns trigger language plpgsql as $$
begin
    new.body := new.body || ' (touched)';
    return new;
end $$;
create trigger note_touch before insert on shop.note for each row execute function shop.touch_note();

--changeset e2e:source-drift context:source
alter table shop.emp add column salary numeric(10,2);
create table shop.legacy (id int primary key, x text);

--changeset e2e:target-drift context:target
alter table shop.emp add column nickname text;
alter table shop.emp alter column title type varchar(60);
alter table shop.mismatch alter column v type integer using null;

--changeset e2e:source-data context:source splitStatements:false
insert into shop.dept select g, 'dept ' || g, null from generate_series(1, 50) g;
insert into shop.emp select g, (g % 50) + 1, 'emp ' || g, 'title ' || (g % 7), g * 10.5 from generate_series(1, 5000) g;
update shop.dept set manager_id = id * 10;
insert into shop.product select g, (g % 100) + 0.99, g % 9 from generate_series(1, 2000) g;
insert into shop.ticket (subject) select 't' || g from generate_series(1, 300) g;
insert into shop.note (body) select 'n' || g from generate_series(1, 400) g;
insert into shop.ledger select 'acct-' || (g % 37), g, g * 1.25 from generate_series(1, 60000) g;
insert into shop.visit select md5(g::text)::uuid, timestamptz '2025-01-01' + g * interval '1 minute' from generate_series(1, 30000) g;
insert into shop.event_log select timestamptz '2025-01-01' + g * interval '1 second', 'e' || g from generate_series(1, 5000) g;
insert into shop.document select g, lo_from_bytea(0, convert_to(repeat('doc ' || g || ' ', 2000), 'UTF8')) from generate_series(1, 30) g;
insert into shop.document values (31, null);
insert into shop.measurement select g, g % 100, sqrt(g), timestamptz '2024-01-01' + g * interval '1 second' from generate_series(1, 300000) g;
insert into shop.mismatch select g, 'v' || g from generate_series(1, 10) g;
insert into shop.legacy select g, 'x' from generate_series(1, 10) g;

create role app_ro login password 'ro-pw';
grant usage on schema shop to app_ro;
grant select on all tables in schema shop to app_ro;
do $$ declare o oid; begin
    for o in select body from shop.document where body is not null loop
        execute format('grant select on large object %s to app_ro', o);
    end loop;
end $$;
analyze;
