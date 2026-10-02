--liquibase formatted sql

--changeset revenium:001-btree-gist
-- Needed for the exclusion constraint that forbids overlapping price rules.
CREATE EXTENSION IF NOT EXISTS btree_gist;

--changeset revenium:002-tenant
CREATE TABLE tenant (
    id       VARCHAR(64)  PRIMARY KEY,
    name     VARCHAR(200) NOT NULL,
    currency VARCHAR(3)   NOT NULL
);

--changeset revenium:003-price-rule
CREATE TABLE price_rule (
    id               UUID           PRIMARY KEY,
    tenant_id        VARCHAR(64)    NOT NULL REFERENCES tenant (id),
    transaction_code VARCHAR(64)    NOT NULL,
    unit_price       NUMERIC(19, 6) NOT NULL,
    effective_from   TIMESTAMPTZ    NOT NULL,
    effective_to     TIMESTAMPTZ,
    created_at       TIMESTAMPTZ    NOT NULL,
    CONSTRAINT price_rule_unit_price_ck CHECK (unit_price >= 0),
    CONSTRAINT price_rule_effective_range_ck CHECK (effective_to IS NULL OR effective_to > effective_from),
    CONSTRAINT price_rule_tenant_id_uk UNIQUE (tenant_id, id),
    -- At most one rule can be effective for a tenant + transaction code at any instant ([from, to) intervals).
    CONSTRAINT price_rule_no_overlap EXCLUDE USING gist (
        tenant_id WITH =,
        transaction_code WITH =,
        tstzrange(effective_from, effective_to, '[)') WITH &&
    )
);

--changeset revenium:004-usage-transaction
CREATE TABLE usage_transaction (
    id               UUID           PRIMARY KEY,
    tenant_id        VARCHAR(64)    NOT NULL REFERENCES tenant (id),
    event_id         VARCHAR(128)   NOT NULL,
    customer_id      VARCHAR(128)   NOT NULL,
    transaction_code VARCHAR(64)    NOT NULL,
    occurred_at      TIMESTAMPTZ    NOT NULL,
    quantity         NUMERIC(19, 6) NOT NULL,
    fingerprint      VARCHAR(64)    NOT NULL,
    raw_payload      TEXT           NOT NULL,
    received_at      TIMESTAMPTZ    NOT NULL,
    status           VARCHAR(16)    NOT NULL,
    attempts         INT            NOT NULL DEFAULT 0,
    next_attempt_at  TIMESTAMPTZ    NOT NULL,
    status_reason    TEXT,
    processed_at     TIMESTAMPTZ,
    CONSTRAINT usage_transaction_quantity_ck CHECK (quantity > 0),
    CONSTRAINT usage_transaction_status_ck CHECK (status IN ('PENDING', 'RATED', 'UNRATED', 'FAILED')),
    -- Idempotency key: one accepted transaction per tenant + eventId.
    CONSTRAINT usage_transaction_event_uk UNIQUE (tenant_id, event_id),
    CONSTRAINT usage_transaction_tenant_id_uk UNIQUE (tenant_id, id)
);

CREATE INDEX usage_transaction_due_idx ON usage_transaction (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX usage_transaction_customer_idx ON usage_transaction (tenant_id, customer_id, occurred_at);

--changeset revenium:005-processing-log
-- Append-only history of every processing outcome, so reprocessing never erases earlier results.
CREATE TABLE transaction_processing_log (
    id             UUID        PRIMARY KEY,
    tenant_id      VARCHAR(64) NOT NULL,
    transaction_id UUID        NOT NULL,
    event_type     VARCHAR(32) NOT NULL,
    detail         TEXT,
    recorded_at    TIMESTAMPTZ NOT NULL,
    CONSTRAINT processing_log_transaction_fk FOREIGN KEY (tenant_id, transaction_id)
        REFERENCES usage_transaction (tenant_id, id)
);

CREATE INDEX transaction_processing_log_transaction_idx ON transaction_processing_log (tenant_id, transaction_id);

--changeset revenium:006-duplicate-delivery
CREATE TABLE duplicate_delivery (
    id                  UUID         PRIMARY KEY,
    tenant_id           VARCHAR(64)  NOT NULL,
    transaction_id      UUID         NOT NULL,
    event_id            VARCHAR(128) NOT NULL,
    fingerprint_matches BOOLEAN      NOT NULL,
    raw_payload         TEXT         NOT NULL,
    received_at         TIMESTAMPTZ  NOT NULL,
    CONSTRAINT duplicate_delivery_transaction_fk FOREIGN KEY (tenant_id, transaction_id)
        REFERENCES usage_transaction (tenant_id, id)
);

CREATE INDEX duplicate_delivery_transaction_idx ON duplicate_delivery (tenant_id, transaction_id);

--changeset revenium:007-rejected-submission
CREATE TABLE rejected_submission (
    id          UUID         PRIMARY KEY,
    tenant_id   VARCHAR(64)  NOT NULL REFERENCES tenant (id),
    event_id    VARCHAR(128),
    customer_id VARCHAR(128),
    occurred_at TIMESTAMPTZ,
    reasons     TEXT         NOT NULL,
    raw_payload TEXT         NOT NULL,
    received_at TIMESTAMPTZ  NOT NULL
);

CREATE INDEX rejected_submission_customer_idx ON rejected_submission (tenant_id, customer_id);

--changeset revenium:008-invoice
CREATE TABLE invoice (
    id                UUID           PRIMARY KEY,
    tenant_id         VARCHAR(64)    NOT NULL REFERENCES tenant (id),
    customer_id       VARCHAR(128)   NOT NULL,
    billing_period    DATE           NOT NULL,
    currency          VARCHAR(3)     NOT NULL,
    transaction_count INT            NOT NULL,
    total_amount      NUMERIC(19, 4) NOT NULL,
    issued_at         TIMESTAMPTZ    NOT NULL,
    CONSTRAINT invoice_billing_period_ck CHECK (EXTRACT(DAY FROM billing_period) = 1),
    -- An issued invoice closes the period: never two invoices for the same customer and month.
    CONSTRAINT invoice_period_uk UNIQUE (tenant_id, customer_id, billing_period),
    CONSTRAINT invoice_tenant_id_uk UNIQUE (tenant_id, id)
);

CREATE TABLE invoice_line (
    id                UUID           PRIMARY KEY,
    tenant_id         VARCHAR(64)    NOT NULL,
    invoice_id        UUID           NOT NULL,
    transaction_code  VARCHAR(64)    NOT NULL,
    transaction_count INT            NOT NULL,
    quantity          NUMERIC(19, 6) NOT NULL,
    amount            NUMERIC(19, 4) NOT NULL,
    CONSTRAINT invoice_line_code_uk UNIQUE (invoice_id, transaction_code),
    CONSTRAINT invoice_line_invoice_fk FOREIGN KEY (tenant_id, invoice_id) REFERENCES invoice (tenant_id, id)
);

--changeset revenium:009-rated-charge
CREATE TABLE rated_charge (
    id             UUID           PRIMARY KEY,
    tenant_id      VARCHAR(64)    NOT NULL,
    transaction_id UUID           NOT NULL,
    price_rule_id  UUID           NOT NULL,
    unit_price     NUMERIC(19, 6) NOT NULL,
    amount         NUMERIC(19, 4) NOT NULL,
    currency       VARCHAR(3)     NOT NULL,
    billing_period DATE           NOT NULL,
    late_arrival   BOOLEAN        NOT NULL,
    rated_at       TIMESTAMPTZ    NOT NULL,
    invoice_id     UUID,
    CONSTRAINT rated_charge_amount_ck CHECK (amount >= 0),
    CONSTRAINT rated_charge_billing_period_ck CHECK (EXTRACT(DAY FROM billing_period) = 1),
    -- One charge per accepted transaction: the database-level guarantee against double billing.
    CONSTRAINT rated_charge_transaction_uk UNIQUE (transaction_id),
    -- Composite foreign keys keep every reference inside the same tenant.
    CONSTRAINT rated_charge_transaction_fk FOREIGN KEY (tenant_id, transaction_id)
        REFERENCES usage_transaction (tenant_id, id),
    CONSTRAINT rated_charge_price_rule_fk FOREIGN KEY (tenant_id, price_rule_id)
        REFERENCES price_rule (tenant_id, id),
    CONSTRAINT rated_charge_invoice_fk FOREIGN KEY (tenant_id, invoice_id)
        REFERENCES invoice (tenant_id, id)
);

CREATE INDEX rated_charge_period_idx ON rated_charge (tenant_id, billing_period) WHERE invoice_id IS NULL;
CREATE INDEX rated_charge_invoice_idx ON rated_charge (invoice_id);
