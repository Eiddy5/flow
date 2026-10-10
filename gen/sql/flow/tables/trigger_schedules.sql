-- One durable pending occurrence per Flow trigger; all times are UTC milliseconds.
CREATE TABLE IF NOT EXISTS trigger_schedules (
    company_id varchar(64) NOT NULL,
    id varchar(64) NOT NULL,
    flow_key varchar(128) NOT NULL,
    trigger_key varchar(128) NOT NULL,
    schedule text NOT NULL,
    scheduled_at bigint NOT NULL,
    event_id varchar(64) NOT NULL,
    execution_id varchar(64) NOT NULL,
    available_at bigint NOT NULL,
    attempts integer NOT NULL,
    last_failure_at bigint,
    last_error text,
    CONSTRAINT pk_trigger_schedules PRIMARY KEY (company_id, id),
    CONSTRAINT uq_trigger_schedules_trigger UNIQUE (company_id, flow_key, trigger_key)
);
CREATE INDEX IF NOT EXISTS idx_trigger_schedules_available ON trigger_schedules (available_at);
