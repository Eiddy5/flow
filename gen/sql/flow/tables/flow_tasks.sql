-- Only deployed Flow versions materialize Task definition snapshot rows.
CREATE TABLE IF NOT EXISTS flow_tasks (
    company_id       varchar(64) NOT NULL,
    flow_key         varchar(128) NOT NULL,

    flow_version     bigint NOT NULL,
    id               varchar(64) NOT NULL,
    key              varchar(128) NOT NULL,
    display_name     text NOT NULL,
    type             text NOT NULL,

    parent_id        varchar(64),
    position         integer NOT NULL,

    inputs           jsonb NOT NULL,
    outputs          jsonb NOT NULL,
    properties       jsonb NOT NULL,

    CONSTRAINT pk_flow_tasks
        PRIMARY KEY (company_id, flow_key, flow_version, id),
    CONSTRAINT uq_flow_tasks_key
        UNIQUE (company_id, flow_key, flow_version, key)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_flow_tasks_sibling_position
    ON flow_tasks (
        company_id,
        flow_key,
        flow_version,
        COALESCE(parent_id, ''),
        position
    );

CREATE INDEX IF NOT EXISTS idx_flow_tasks_tree
    ON flow_tasks (
        company_id,
        flow_key,
        flow_version,
        parent_id,
        position
    );
