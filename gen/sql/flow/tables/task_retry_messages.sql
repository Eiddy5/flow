-- Reliable publication to Pulsar; deliver_at is interpreted by the broker, not polled here.
CREATE TABLE IF NOT EXISTS task_retry_messages (
    company_id varchar(64) NOT NULL,
    execution_id varchar(64) NOT NULL,
    task_run_id varchar(64) NOT NULL,
    attempt_number integer NOT NULL,
    deliver_at bigint NOT NULL,
    published_at bigint,
    CONSTRAINT pk_task_retry_messages PRIMARY KEY (company_id, execution_id, task_run_id, attempt_number)
);
CREATE INDEX IF NOT EXISTS idx_task_retry_messages_pending
    ON task_retry_messages (deliver_at) WHERE published_at IS NULL;
