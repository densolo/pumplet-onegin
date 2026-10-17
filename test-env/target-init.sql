-- Target fixture: the schema as Liquibase would create it before a copy (no data).
-- Generated from the source with: pg_dump --schema-only --no-owner --no-privileges -n app

CREATE SCHEMA app;

CREATE FUNCTION app.audit_orders() RETURNS trigger
    LANGUAGE plpgsql
    AS $$
BEGIN
    INSERT INTO app.audit_log (table_name, row_id) VALUES (TG_TABLE_NAME, NEW.id);
    RETURN NEW;
END $$;

CREATE TABLE app."OrderNotes" (
    "Id" bigint NOT NULL,
    "orderId" bigint,
    "Note" text
);

CREATE TABLE app.audit_log (
    id bigint NOT NULL,
    table_name text NOT NULL,
    row_id bigint,
    changed_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE SEQUENCE app.audit_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE app.audit_log_id_seq OWNED BY app.audit_log.id;

CREATE TABLE app.customers (
    id bigint NOT NULL,
    email text NOT NULL,
    name text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE TABLE app.order_items (
    order_id bigint NOT NULL,
    line_no integer NOT NULL,
    product_id integer NOT NULL,
    qty integer NOT NULL
);

CREATE TABLE app.orders (
    id bigint NOT NULL,
    customer_id bigint NOT NULL,
    status text NOT NULL,
    total numeric(14,2) NOT NULL,
    placed_at timestamp with time zone NOT NULL
);

CREATE TABLE app.products (
    id integer NOT NULL,
    sku text NOT NULL,
    price numeric(12,2) NOT NULL
);

CREATE TABLE app.type_zoo (
    id integer NOT NULL,
    c_bool boolean,
    c_small smallint,
    c_num numeric,
    c_real real,
    c_double double precision,
    c_text text,
    c_varchar character varying(20),
    c_char character(3),
    c_bytea bytea,
    c_date date,
    c_ts timestamp without time zone,
    c_tstz timestamp with time zone,
    c_interval interval,
    c_uuid uuid,
    c_json json,
    c_jsonb jsonb,
    c_int_arr integer[],
    c_txt_arr text[],
    c_inet inet
);

ALTER TABLE ONLY app.audit_log ALTER COLUMN id SET DEFAULT nextval('app.audit_log_id_seq'::regclass);

ALTER TABLE ONLY app."OrderNotes"
    ADD CONSTRAINT "OrderNotes_pkey" PRIMARY KEY ("Id");

ALTER TABLE ONLY app.audit_log
    ADD CONSTRAINT audit_log_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.customers
    ADD CONSTRAINT customers_email_key UNIQUE (email);

ALTER TABLE ONLY app.customers
    ADD CONSTRAINT customers_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.order_items
    ADD CONSTRAINT order_items_pkey PRIMARY KEY (order_id, line_no);

ALTER TABLE ONLY app.orders
    ADD CONSTRAINT orders_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.products
    ADD CONSTRAINT products_pkey PRIMARY KEY (id);

ALTER TABLE ONLY app.products
    ADD CONSTRAINT products_sku_key UNIQUE (sku);

ALTER TABLE ONLY app.type_zoo
    ADD CONSTRAINT type_zoo_pkey PRIMARY KEY (id);

CREATE INDEX order_items_product_idx ON app.order_items USING btree (product_id);

CREATE INDEX orders_customer_idx ON app.orders USING btree (customer_id);

CREATE INDEX orders_placed_at_idx ON app.orders USING btree (placed_at);

CREATE TRIGGER orders_audit AFTER INSERT OR UPDATE ON app.orders FOR EACH ROW EXECUTE FUNCTION app.audit_orders();

ALTER TABLE ONLY app."OrderNotes"
    ADD CONSTRAINT "OrderNotes_orderId_fkey" FOREIGN KEY ("orderId") REFERENCES app.orders(id);

ALTER TABLE ONLY app.order_items
    ADD CONSTRAINT order_items_order_id_fkey FOREIGN KEY (order_id) REFERENCES app.orders(id);

ALTER TABLE ONLY app.order_items
    ADD CONSTRAINT order_items_product_id_fkey FOREIGN KEY (product_id) REFERENCES app.products(id);

ALTER TABLE ONLY app.orders
    ADD CONSTRAINT orders_customer_id_fkey FOREIGN KEY (customer_id) REFERENCES app.customers(id);

