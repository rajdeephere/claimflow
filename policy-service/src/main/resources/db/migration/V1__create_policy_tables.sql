-- Policy Service schema (PolicyCenter-like): customers hold policies, policies have coverages.
-- Constraints here are the last line of defence; the API validates the same rules first.

CREATE TABLE customers (
    id             UUID         PRIMARY KEY,
    first_name     VARCHAR(100) NOT NULL,
    last_name      VARCHAR(100) NOT NULL,
    email          VARCHAR(255) NOT NULL,
    phone          VARCHAR(20),
    date_of_birth  DATE         NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL,
    updated_at     TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_customers_email UNIQUE (email)
);

-- Human-readable policy numbers: POL-<year>-<6 digits>
CREATE SEQUENCE policy_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE policies (
    id             UUID          PRIMARY KEY,
    policy_number  VARCHAR(20)   NOT NULL,
    customer_id    UUID          NOT NULL REFERENCES customers (id),
    product_type   VARCHAR(20)   NOT NULL,
    status         VARCHAR(20)   NOT NULL,
    start_date     DATE          NOT NULL,
    end_date       DATE          NOT NULL,
    premium        NUMERIC(15,2) NOT NULL,
    version        BIGINT        NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ   NOT NULL,
    updated_at     TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uk_policies_policy_number UNIQUE (policy_number),
    CONSTRAINT ck_policies_dates   CHECK (end_date > start_date),
    CONSTRAINT ck_policies_premium CHECK (premium > 0),
    CONSTRAINT ck_policies_product CHECK (product_type IN ('MOTOR', 'HOME', 'HEALTH')),
    CONSTRAINT ck_policies_status  CHECK (status IN ('ACTIVE', 'CANCELLED'))
);

-- "All policies of a customer": FKs are not indexed automatically in PostgreSQL.
CREATE INDEX idx_policies_customer_id ON policies (customer_id);

CREATE TABLE coverages (
    id             UUID          PRIMARY KEY,
    policy_id      UUID          NOT NULL REFERENCES policies (id) ON DELETE CASCADE,
    coverage_type  VARCHAR(30)   NOT NULL,
    limit_amount   NUMERIC(15,2) NOT NULL,
    deductible     NUMERIC(15,2) NOT NULL,
    -- One coverage of each type per policy; this unique index also serves lookups by policy_id.
    CONSTRAINT uk_coverages_policy_type UNIQUE (policy_id, coverage_type),
    CONSTRAINT ck_coverages_limit       CHECK (limit_amount > 0),
    CONSTRAINT ck_coverages_deductible  CHECK (deductible >= 0 AND deductible < limit_amount)
);
