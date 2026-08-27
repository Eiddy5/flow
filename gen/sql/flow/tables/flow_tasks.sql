CREATE TABLE IF NOT EXISTS flow_tasks (
    company_id       varchar(64) NOT NULL,
    id               varchar(64) NOT NULL,
    type             text NOT NULL,
    route            text NOT NULL DEFAULT 'DIRECT',
    inputs           jsonb NOT NULL DEFAULT '[]'::jsonb,
    outputs          jsonb NOT NULL DEFAULT '[]'::jsonb,
    properties       jsonb NOT NULL DEFAULT '{}'::jsonb,
    flow_key         varchar(128) NOT NULL,
    flow_version      bigint NOT NULL,
    parent_id        varchar(64),
    "order"          integer NOT NULL,
    key              varchar(128) NOT NULL,
    depend_on        jsonb NOT NULL DEFAULT '[]'::jsonb,

    CONSTRAINT pk_flow_tasks
        PRIMARY KEY (company_id, flow_key, flow_version, id),
    CONSTRAINT uq_flow_tasks_key
        UNIQUE (company_id, flow_key, flow_version, key)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_flow_tasks_order
    ON flow_tasks (
        company_id,
        flow_key,
        flow_version,
        COALESCE(parent_id, ''),
        "order"
    );

CREATE INDEX IF NOT EXISTS idx_flow_tasks_tree
    ON flow_tasks (
        company_id,
        flow_key,
        flow_version,
        parent_id,
        "order"
    );

CREATE INDEX IF NOT EXISTS idx_flow_tasks_type
    ON flow_tasks (company_id, type);
