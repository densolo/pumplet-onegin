-- Source fixture: schema `app` with FKs, secondary indexes, a user trigger,
-- an excluded audit table, odd identifiers and a wide range of column types.
-- Also a schema `other` that is not listed in the job's source.schemas.

CREATE ROLE app_ro LOGIN PASSWORD 'app-ro-pw';

CREATE SCHEMA app;
CREATE SCHEMA other;

CREATE TABLE app.customers (
    id         bigint PRIMARY KEY,
    email      text NOT NULL UNIQUE,
    name       text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app.products (
    id    int PRIMARY KEY,
    sku   text NOT NULL UNIQUE,
    price numeric(12, 2) NOT NULL
);

CREATE TABLE app.orders (
    id          bigint PRIMARY KEY,
    customer_id bigint NOT NULL REFERENCES app.customers (id),
    status      text NOT NULL,
    total       numeric(14, 2) NOT NULL,
    placed_at   timestamptz NOT NULL
);
CREATE INDEX orders_customer_idx ON app.orders (customer_id);
CREATE INDEX orders_placed_at_idx ON app.orders (placed_at);

CREATE TABLE app.order_items (
    order_id   bigint NOT NULL REFERENCES app.orders (id),
    line_no    int NOT NULL,
    product_id int NOT NULL REFERENCES app.products (id),
    qty        int NOT NULL,
    PRIMARY KEY (order_id, line_no)
);
CREATE INDEX order_items_product_idx ON app.order_items (product_id);

CREATE TABLE app.audit_log (
    id         bigserial PRIMARY KEY,
    table_name text NOT NULL,
    row_id     bigint,
    changed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE app."OrderNotes" (
    "Id"      bigint PRIMARY KEY,
    "orderId" bigint REFERENCES app.orders (id),
    "Note"    text
);

CREATE TABLE app.type_zoo (
    id        int PRIMARY KEY,
    c_bool    boolean,
    c_small   smallint,
    c_num     numeric,
    c_real    real,
    c_double  double precision,
    c_text    text,
    c_varchar varchar(20),
    c_char    char(3),
    c_bytea   bytea,
    c_date    date,
    c_ts      timestamp,
    c_tstz    timestamptz,
    c_interval interval,
    c_uuid    uuid,
    c_json    json,
    c_jsonb   jsonb,
    c_int_arr int[],
    c_txt_arr text[],
    c_inet    inet
);

CREATE TABLE other.ignored (id int PRIMARY KEY);

-- Seed data. orders is above the job's minRowsToSplit so it gets chunked.
INSERT INTO app.customers (id, email, name, created_at)
SELECT g, 'user' || g || '@example.com', 'Customer ' || g, timestamptz '2020-01-01' + g * interval '1 minute'
FROM generate_series(1, 10000) g;

INSERT INTO app.products (id, sku, price)
SELECT g, 'SKU-' || lpad(g::text, 5, '0'), round((random() * 500 + 1)::numeric, 2)
FROM generate_series(1, 500) g;

INSERT INTO app.orders (id, customer_id, status, total, placed_at)
SELECT g, 1 + (g % 10000), (ARRAY['new','paid','shipped','cancelled'])[1 + g % 4],
       round((random() * 1000)::numeric, 2), timestamptz '2021-01-01' + g * interval '37 seconds'
FROM generate_series(1, 600000) g;

INSERT INTO app.order_items (order_id, line_no, product_id, qty)
SELECT o, l, 1 + ((o * 7 + l) % 500), 1 + (o + l) % 5
FROM generate_series(1, 600000) o, generate_series(1, 2) l;

INSERT INTO app.audit_log (table_name, row_id)
SELECT 'orders', g FROM generate_series(1, 50000) g;

INSERT INTO app."OrderNotes" ("Id", "orderId", "Note")
SELECT g, g, CASE WHEN g % 3 = 0 THEN NULL ELSE 'note ' || g || E' with\ttab and "quotes"' END
FROM generate_series(1, 1000) g;

INSERT INTO app.type_zoo VALUES
  (1, true, 1, 12345678901234567890.123456789, 1.5, 2.25, 'plain', 'vc', 'abc', '\xdeadbeef',
   '2024-02-29', '2024-02-29 12:34:56.789', '2024-02-29 12:34:56.789+02', '1 day 2 hours',
   'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11', '{"a": 1}', '{"b": [1, 2]}', '{1,2,3}', '{"x","y z"}', '192.168.0.1'),
  (2, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL),
  (3, false, -32768, -0.000001, 'NaN', 'Infinity', E'multi\nline\\back', '', '   ', '\x', 'infinity', '-infinity',
   '1970-01-01 00:00:00+00', '-1 years', '00000000-0000-0000-0000-000000000000', 'null', '[]', '{}', '{NULL,""}', '::1');

INSERT INTO other.ignored SELECT generate_series(1, 10);

-- User trigger (created after seeding so it didn't fire for the seed rows).
CREATE FUNCTION app.audit_orders() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO app.audit_log (table_name, row_id) VALUES (TG_TABLE_NAME, NEW.id);
    RETURN NEW;
END $$;
CREATE TRIGGER orders_audit AFTER INSERT OR UPDATE ON app.orders
    FOR EACH ROW EXECUTE FUNCTION app.audit_orders();

-- Read-only access for the job user: everything in app, nothing in other.
GRANT USAGE ON SCHEMA app TO app_ro;
GRANT SELECT ON ALL TABLES IN SCHEMA app TO app_ro;

ANALYZE;
