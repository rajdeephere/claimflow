-- Payment Service schema (BillingCenter-like): how much to pay (settlements) and the payment itself.

-- The settlement calculation, kept so every payout can be explained: approved - deductible, capped at the limit.
CREATE TABLE settlements (
    id               UUID          PRIMARY KEY,
    claim_id         UUID          NOT NULL,
    claim_number     VARCHAR(20)   NOT NULL,
    claimed_amount   NUMERIC(15,2) NOT NULL,
    approved_amount  NUMERIC(15,2) NOT NULL,
    deductible       NUMERIC(15,2) NOT NULL,
    coverage_limit   NUMERIC(15,2) NOT NULL,
    payable_amount   NUMERIC(15,2) NOT NULL,
    capped_at_limit  BOOLEAN       NOT NULL,
    calculated_at    TIMESTAMPTZ   NOT NULL,
    CONSTRAINT uk_settlements_claim UNIQUE (claim_id),
    CONSTRAINT ck_settlements_payable CHECK (payable_amount > 0 AND payable_amount <= coverage_limit)
);

CREATE TABLE payments (
    id                 UUID          PRIMARY KEY,   -- also the idempotency key sent to the gateway
    claim_id           UUID          NOT NULL,
    claim_number       VARCHAR(20)   NOT NULL,
    settlement_id      UUID          NOT NULL REFERENCES settlements (id),
    amount             NUMERIC(15,2) NOT NULL,
    status             VARCHAR(20)   NOT NULL,
    gateway_reference  VARCHAR(100),
    failure_reason     VARCHAR(500),
    attempts           INT           NOT NULL DEFAULT 0,
    correlation_id     VARCHAR(100),
    version            BIGINT        NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ   NOT NULL,
    updated_at         TIMESTAMPTZ   NOT NULL,
    completed_at       TIMESTAMPTZ,
    -- Last line of defence against paying a claim twice, whatever happens in the application.
    CONSTRAINT uk_payments_claim  UNIQUE (claim_id),
    CONSTRAINT ck_payments_amount CHECK (amount > 0),
    CONSTRAINT ck_payments_status CHECK (status IN ('INITIATED', 'COMPLETED', 'FAILED'))
);

-- The processor polls for INITIATED payments; a partial index keeps that cheap as completed rows pile up.
CREATE INDEX idx_payments_initiated ON payments (created_at) WHERE status = 'INITIATED';

-- Same outbox and idempotent-consumer tables as claim-service (ADR-0012, ADR-0009).
CREATE TABLE outbox_events (
    id              UUID          PRIMARY KEY,
    seq             BIGSERIAL     NOT NULL,
    topic           VARCHAR(100)  NOT NULL,
    message_key     VARCHAR(100)  NOT NULL,
    event_type      VARCHAR(50)   NOT NULL,
    payload         TEXT          NOT NULL,
    correlation_id  VARCHAR(100),
    created_at      TIMESTAMPTZ   NOT NULL,
    published_at    TIMESTAMPTZ,
    attempts        INT           NOT NULL DEFAULT 0,
    last_error      VARCHAR(1000)
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (seq) WHERE published_at IS NULL;

CREATE TABLE processed_events (
    event_id       UUID          NOT NULL,
    consumer_name  VARCHAR(100)  NOT NULL,
    event_type     VARCHAR(50)   NOT NULL,
    processed_at   TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (event_id, consumer_name)
);
