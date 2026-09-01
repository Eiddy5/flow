CREATE TABLE IF NOT EXISTS executions (
    id               varchar(64) NOT NULL,
    company_id       varchar(64) NOT NULL,
    flow_key         varchar(128) NOT NULL,
    flow_version      bigint NOT NULL,
    state            jsonb NOT NULL,
    generation       jsonb NOT NULL DEFAULT '{"current": null, "history": []}'::jsonb,
    lock_version     bigint NOT NULL DEFAULT 0,

    creator          jsonb NOT NULL,
    created_at       bigint NOT NULL,
    inputs           jsonb NOT NULL DEFAULT '{}'::jsonb,

    CONSTRAINT pk_executions
        PRIMARY KEY (company_id, id),
    CONSTRAINT uq_executions_id
        UNIQUE (id),
    CONSTRAINT uq_executions_flow
        UNIQUE (company_id, id, flow_key, flow_version)
);

CREATE INDEX IF NOT EXISTS idx_executions_flow
    ON executions (company_id, flow_key, flow_version);

CREATE INDEX IF NOT EXISTS idx_executions_state_current
    ON executions (company_id, (state ->> 'current'));
