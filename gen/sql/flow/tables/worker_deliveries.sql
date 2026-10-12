-- One current execution permit per TaskRun; business attempts remain in the aggregate.
CREATE TABLE IF NOT EXISTS worker_deliveries (
    company_id varchar(64) NOT NULL,
    execution_id varchar(64) NOT NULL,
    task_run_id varchar(64) NOT NULL,
    attempt_number integer NOT NULL,
    delivery_generation bigint NOT NULL,
    owner_id varchar(64),
    lease_until_at bigint,
    phase varchar(32) NOT NULL,
    result_id varchar(64),
    CONSTRAINT pk_worker_deliveries PRIMARY KEY (company_id, execution_id, task_run_id)
);
CREATE INDEX IF NOT EXISTS idx_worker_deliveries_leases
    ON worker_deliveries (lease_until_at) WHERE phase = 'RUNNING';
