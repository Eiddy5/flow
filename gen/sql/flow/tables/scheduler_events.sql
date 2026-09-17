-- Durable application outbox; published rows are retained until explicit retention.
CREATE TABLE IF NOT EXISTS scheduler_events (
    company_id varchar(64) NOT NULL,
    id varchar(64) NOT NULL,
    kind varchar(32) NOT NULL,
    payload jsonb NOT NULL,
    created_at bigint NOT NULL,
    published_at bigint,
    processed_at bigint,
    retry_at bigint NOT NULL,
    attempts integer NOT NULL,
    last_error text,
    CONSTRAINT pk_scheduler_events PRIMARY KEY (company_id, id)
);
CREATE INDEX IF NOT EXISTS idx_scheduler_events_pending
    ON scheduler_events (retry_at) WHERE published_at IS NULL;
