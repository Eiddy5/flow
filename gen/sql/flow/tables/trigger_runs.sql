-- One accepted trigger occurrence, retaining stable event and execution identities.
CREATE TABLE IF NOT EXISTS trigger_runs (
    company_id varchar(64) NOT NULL,
    id varchar(64) NOT NULL,
    flow_key varchar(128) NOT NULL,
    flow_version bigint NOT NULL,
    trigger_key varchar(128) NOT NULL,
    scheduled_at bigint,
    source_key varchar(256) NOT NULL,
    execution_id varchar(64) NOT NULL,
    actor_id varchar(64) NOT NULL,
    inputs jsonb NOT NULL,
    accepted_at bigint,
    CONSTRAINT pk_trigger_runs PRIMARY KEY (company_id, id),
    CONSTRAINT uq_trigger_runs_execution UNIQUE (company_id, execution_id),
    CONSTRAINT uq_trigger_runs_occurrence UNIQUE (company_id, flow_key, trigger_key, source_key)
);
