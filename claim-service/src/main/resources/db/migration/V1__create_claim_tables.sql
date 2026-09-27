-- Claim Service schema (ClaimCenter-like).
-- policy_id is a plain UUID, not a foreign key: policies live in another service's database.

CREATE TABLE adjusters (
    id          UUID         PRIMARY KEY,
    name        VARCHAR(150) NOT NULL,
    email       VARCHAR(255) NOT NULL,
    active      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uk_adjusters_email UNIQUE (email)
);

-- Human-readable claim numbers: CLM-<year>-<6 digits>
CREATE SEQUENCE claim_number_seq START WITH 1 INCREMENT BY 1;

CREATE TABLE claims (
    id                UUID          PRIMARY KEY,
    claim_number      VARCHAR(20)   NOT NULL,
    policy_id         UUID          NOT NULL,
    loss_type         VARCHAR(30)   NOT NULL,
    incident_date     DATE          NOT NULL,
    reported_at       TIMESTAMPTZ   NOT NULL,
    description       VARCHAR(2000) NOT NULL,
    claimed_amount    NUMERIC(15,2) NOT NULL,
    approved_amount   NUMERIC(15,2),
    status            VARCHAR(30)   NOT NULL,
    adjuster_id       UUID          REFERENCES adjusters (id),
    rejection_reason  VARCHAR(500),
    -- Client-supplied key so a retried FNOL returns the original claim instead of creating a duplicate.
    idempotency_key   VARCHAR(100),
    version           BIGINT        NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL,
    updated_at        TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uk_claims_claim_number    UNIQUE (claim_number),
    CONSTRAINT uk_claims_idempotency_key UNIQUE (idempotency_key),
    CONSTRAINT ck_claims_claimed_amount  CHECK (claimed_amount > 0),
    CONSTRAINT ck_claims_approved_amount CHECK (approved_amount IS NULL
                                                OR (approved_amount > 0 AND approved_amount <= claimed_amount)),
    CONSTRAINT ck_claims_status CHECK (status IN ('SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED',
                                                  'SETTLEMENT_PENDING', 'PAYMENT_INITIATED', 'SETTLED', 'CLOSED'))
);

-- NOTE: the composite index for "claims of a policy by status, newest first" is added in Phase 7
-- (V2 migration) together with EXPLAIN ANALYZE before/after measurements.
CREATE INDEX idx_claims_adjuster_id ON claims (adjuster_id);

-- Append-only audit trail. Rows are never updated or deleted.
CREATE TABLE claim_history (
    id              BIGSERIAL     PRIMARY KEY,
    claim_id        UUID          NOT NULL REFERENCES claims (id),
    event_type      VARCHAR(40)   NOT NULL,
    old_status      VARCHAR(30),
    new_status      VARCHAR(30)   NOT NULL,
    performed_by    VARCHAR(100)  NOT NULL,
    correlation_id  VARCHAR(100),
    details         VARCHAR(1000),
    created_at      TIMESTAMPTZ   NOT NULL
);

CREATE INDEX idx_claim_history_claim_created ON claim_history (claim_id, created_at, id);
