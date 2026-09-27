-- Transactional outbox (ADR-0012): events are written here in the SAME transaction as the claim
-- change, then published to Kafka by OutboxRelay. No lost events, no events for rolled-back changes.
CREATE TABLE outbox_events (
    id              UUID          PRIMARY KEY,          -- = EventEnvelope.eventId
    seq             BIGSERIAL     NOT NULL,             -- publish order (created_at can tie within one tx)
    topic           VARCHAR(100)  NOT NULL,
    message_key     VARCHAR(100)  NOT NULL,             -- claimId: keeps a claim's events in one partition
    event_type      VARCHAR(50)   NOT NULL,
    payload         TEXT          NOT NULL,             -- the full JSON envelope
    correlation_id  VARCHAR(100),
    created_at      TIMESTAMPTZ   NOT NULL,
    published_at    TIMESTAMPTZ,
    attempts        INT           NOT NULL DEFAULT 0,
    last_error      VARCHAR(1000)
);

-- The relay only ever looks at unpublished rows; a partial index keeps that lookup tiny
-- no matter how many published rows accumulate.
CREATE INDEX idx_outbox_unpublished ON outbox_events (seq) WHERE published_at IS NULL;

-- Idempotent consumer (ADR-0009): one row per event this service has processed.
-- Composite key so several consumers in the same service can share the table.
CREATE TABLE processed_events (
    event_id       UUID          NOT NULL,
    consumer_name  VARCHAR(100)  NOT NULL,
    event_type     VARCHAR(50)   NOT NULL,
    processed_at   TIMESTAMPTZ   NOT NULL,
    PRIMARY KEY (event_id, consumer_name)
);
