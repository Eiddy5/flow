-- Pending reliable handoffs; remove only after successful consumer processing.
CREATE TABLE IF NOT EXISTS execution_messages (
    company_id varchar(64) NOT NULL,
    id varchar(64) NOT NULL,
    execution_id varchar(64) NOT NULL,
    task_run_id varchar(64),
    attempt_number integer,
    delivery_generation bigint,
    kind varchar(32) NOT NULL,
    payload jsonb NOT NULL,
    created_at bigint NOT NULL,
    published_at bigint,
    CONSTRAINT pk_execution_messages PRIMARY KEY (company_id, id)
);
CREATE INDEX IF NOT EXISTS idx_execution_messages_pending
    ON execution_messages (created_at, id) WHERE published_at IS NULL;
CREATE INDEX IF NOT EXISTS idx_execution_messages_task
    ON execution_messages (company_id, execution_id, task_run_id, attempt_number);
