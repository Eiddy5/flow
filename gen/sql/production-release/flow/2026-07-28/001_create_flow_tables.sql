CREATE TABLE IF NOT EXISTS flows (
    id             varchar(64) NOT NULL,
    key            varchar(128) NOT NULL,
    company_id     varchar(64) NOT NULL,
    status         varchar(16) NOT NULL,
    version        bigint NOT NULL,
    revision       bigint NOT NULL,
    description    text NOT NULL DEFAULT '',

    creator        jsonb NOT NULL,
    creator_id     varchar(64)
        GENERATED ALWAYS AS (creator ->> 'id') STORED,
    updater        jsonb NOT NULL,
    updater_id     varchar(64)
        GENERATED ALWAYS AS (updater ->> 'id') STORED,
    deleter        jsonb,
    deleter_id     varchar(64)
        GENERATED ALWAYS AS (deleter ->> 'id') STORED,

    created_at     bigint NOT NULL DEFAULT (
        (extract(epoch FROM clock_timestamp()) * 1000)::bigint
    ),
    updated_at     bigint NOT NULL DEFAULT (
        (extract(epoch FROM clock_timestamp()) * 1000)::bigint
    ),
    deleted_at     bigint,

    CONSTRAINT pk_flows
        PRIMARY KEY (company_id, id, version),
    CONSTRAINT uq_flows_company_key_version
        UNIQUE (company_id, key, version),
    CONSTRAINT ck_flows_id
        CHECK (length(btrim(id)) > 0),
    CONSTRAINT ck_flows_key
        CHECK (length(btrim(key)) BETWEEN 1 AND 128),
    CONSTRAINT ck_flows_status
        CHECK (status IN ('DEPLOYED', 'CLOSE')),
    CONSTRAINT ck_flows_version
        CHECK (version > 0),
    CONSTRAINT ck_flows_revision
        CHECK (revision > 0),
    CONSTRAINT ck_flows_creator
        CHECK (
            jsonb_typeof(creator) = 'object'
            AND creator ? 'id'
        ),
    CONSTRAINT ck_flows_updater
        CHECK (
            jsonb_typeof(updater) = 'object'
            AND updater ? 'id'
        ),
    CONSTRAINT ck_flows_deleter
        CHECK (
            deleter IS NULL
            OR (
                jsonb_typeof(deleter) = 'object'
                AND deleter ? 'id'
            )
        ),
    CONSTRAINT ck_flows_deleted
        CHECK (
            (deleter IS NULL AND deleted_at IS NULL)
            OR (deleter IS NOT NULL AND deleted_at IS NOT NULL)
        )
);

CREATE INDEX IF NOT EXISTS idx_flows_creator_id
    ON flows (creator_id);

CREATE INDEX IF NOT EXISTS idx_flows_updater_id
    ON flows (updater_id);

CREATE INDEX IF NOT EXISTS idx_flows_deleter_id
    ON flows (deleter_id)
    WHERE deleter_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_flows_deployed
    ON flows (company_id, key)
    WHERE status = 'DEPLOYED';

CREATE TABLE IF NOT EXISTS flow_drafts (
    id               varchar(64) NOT NULL,
    company_id       varchar(64) NOT NULL,
    flow_id          varchar(64) NOT NULL,
    key              varchar(128) NOT NULL,
    source_version   bigint,
    revision         bigint NOT NULL,
    content          text NOT NULL,

    creator          jsonb NOT NULL,
    creator_id       varchar(64)
        GENERATED ALWAYS AS (creator ->> 'id') STORED,
    updater          jsonb NOT NULL,
    updater_id       varchar(64)
        GENERATED ALWAYS AS (updater ->> 'id') STORED,
    deleter          jsonb,
    deleter_id       varchar(64)
        GENERATED ALWAYS AS (deleter ->> 'id') STORED,

    created_at       bigint NOT NULL DEFAULT (
        (extract(epoch FROM clock_timestamp()) * 1000)::bigint
    ),
    updated_at       bigint NOT NULL DEFAULT (
        (extract(epoch FROM clock_timestamp()) * 1000)::bigint
    ),
    deleted_at       bigint,

    CONSTRAINT pk_flow_drafts
        PRIMARY KEY (company_id, id),
    CONSTRAINT uq_flow_drafts_company_key
        UNIQUE (company_id, key),
    CONSTRAINT ck_flow_drafts_id
        CHECK (length(btrim(id)) > 0),
    CONSTRAINT ck_flow_drafts_flow_id
        CHECK (length(btrim(flow_id)) > 0),
    CONSTRAINT ck_flow_drafts_key
        CHECK (length(btrim(key)) BETWEEN 1 AND 128),
    CONSTRAINT ck_flow_drafts_source_version
        CHECK (source_version IS NULL OR source_version > 0),
    CONSTRAINT ck_flow_drafts_revision
        CHECK (revision > 0),
    CONSTRAINT ck_flow_drafts_content
        CHECK (octet_length(content) > 0),
    CONSTRAINT ck_flow_drafts_creator
        CHECK (
            jsonb_typeof(creator) = 'object'
            AND creator ? 'id'
        ),
    CONSTRAINT ck_flow_drafts_updater
        CHECK (
            jsonb_typeof(updater) = 'object'
            AND updater ? 'id'
        ),
    CONSTRAINT ck_flow_drafts_deleter
        CHECK (
            deleter IS NULL
            OR (
                jsonb_typeof(deleter) = 'object'
                AND deleter ? 'id'
            )
        ),
    CONSTRAINT ck_flow_drafts_deleted
        CHECK (
            (deleter IS NULL AND deleted_at IS NULL)
            OR (deleter IS NOT NULL AND deleted_at IS NOT NULL)
        )
);

CREATE INDEX IF NOT EXISTS idx_flow_drafts_flow
    ON flow_drafts (company_id, flow_id, source_version);

CREATE INDEX IF NOT EXISTS idx_flow_drafts_creator_id
    ON flow_drafts (creator_id);

CREATE INDEX IF NOT EXISTS idx_flow_drafts_updater_id
    ON flow_drafts (updater_id);

CREATE INDEX IF NOT EXISTS idx_flow_drafts_deleter_id
    ON flow_drafts (deleter_id)
    WHERE deleter_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS flow_tasks (
    company_id     varchar(64) NOT NULL,
    id             varchar(64) NOT NULL,
    type           varchar(64) NOT NULL,
    description    text NOT NULL DEFAULT '',
    route          text NOT NULL DEFAULT 'DIRECT',
    inputs         jsonb NOT NULL DEFAULT '[]'::jsonb,
    outputs        jsonb NOT NULL DEFAULT '[]'::jsonb,
    properties     jsonb NOT NULL DEFAULT '{}'::jsonb,
    flow_id        varchar(64) NOT NULL,
    flow_version   bigint NOT NULL,
    parent_id      varchar(64),
    "order"        integer NOT NULL,
    key            varchar(128) NOT NULL,

    CONSTRAINT pk_flow_tasks
        PRIMARY KEY (company_id, flow_id, flow_version, id),
    CONSTRAINT uq_flow_tasks_key
        UNIQUE (company_id, flow_id, flow_version, key),
    CONSTRAINT uq_flow_tasks_version_id
        UNIQUE (company_id, flow_id, flow_version, id),
    CONSTRAINT ck_flow_tasks_parent
        CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT ck_flow_tasks_order
        CHECK ("order" >= 0),
    CONSTRAINT ck_flow_tasks_key
        CHECK (length(btrim(key)) BETWEEN 1 AND 128),
    CONSTRAINT ck_flow_tasks_type
        CHECK (length(btrim(type)) BETWEEN 1 AND 64),
    CONSTRAINT ck_flow_tasks_route
        CHECK (length(btrim(route)) > 0),
    CONSTRAINT ck_flow_tasks_inputs
        CHECK (jsonb_typeof(inputs) = 'array'),
    CONSTRAINT ck_flow_tasks_outputs
        CHECK (jsonb_typeof(outputs) = 'array'),
    CONSTRAINT ck_flow_tasks_properties
        CHECK (jsonb_typeof(properties) = 'object'),
    CONSTRAINT ck_flow_tasks_version
        CHECK (flow_version > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_flow_tasks_order
    ON flow_tasks (
        company_id,
        flow_id,
        flow_version,
        COALESCE(parent_id, ''),
        "order"
    );

CREATE INDEX IF NOT EXISTS idx_flow_tasks_tree
    ON flow_tasks (
        company_id,
        flow_id,
        flow_version,
        parent_id,
        "order"
    );

CREATE INDEX IF NOT EXISTS idx_flow_tasks_type
    ON flow_tasks (company_id, type);

CREATE TABLE IF NOT EXISTS executions (
    id              varchar(64) NOT NULL,
    company_id      varchar(64) NOT NULL,
    flow_id         varchar(64) NOT NULL,
    flow_version    bigint NOT NULL,
    status          varchar(16) NOT NULL,
    lock_version    bigint NOT NULL DEFAULT 0,

    creator         jsonb NOT NULL,
    updater         jsonb NOT NULL,
    deleter         jsonb,

    created_at      bigint NOT NULL DEFAULT (
        (extract(epoch FROM clock_timestamp()) * 1000)::bigint
    ),
    updated_at      bigint NOT NULL DEFAULT (
        (extract(epoch FROM clock_timestamp()) * 1000)::bigint
    ),
    deleted_at      bigint,

    CONSTRAINT pk_executions
        PRIMARY KEY (company_id, id),
    CONSTRAINT uq_executions_id
        UNIQUE (id),
    CONSTRAINT uq_executions_flow
        UNIQUE (company_id, id, flow_id, flow_version),
    CONSTRAINT ck_executions_version
        CHECK (flow_version > 0),
    CONSTRAINT ck_executions_status
        CHECK (
            status IN (
                'CREATED',
                'RUNNING',
                'COMPLETED',
                'FAILED',
                'CANCELED'
            )
        ),
    CONSTRAINT ck_executions_lock_version
        CHECK (lock_version >= 0),
    CONSTRAINT ck_executions_creator
        CHECK (
            jsonb_typeof(creator) = 'object'
            AND creator ? 'id'
        ),
    CONSTRAINT ck_executions_updater
        CHECK (
            jsonb_typeof(updater) = 'object'
            AND updater ? 'id'
        ),
    CONSTRAINT ck_executions_deleter
        CHECK (
            deleter IS NULL
            OR (
                jsonb_typeof(deleter) = 'object'
                AND deleter ? 'id'
            )
        ),
    CONSTRAINT ck_executions_deleted
        CHECK (
            (deleter IS NULL AND deleted_at IS NULL)
            OR (deleter IS NOT NULL AND deleted_at IS NOT NULL)
        )
);

CREATE INDEX IF NOT EXISTS idx_executions_flow
    ON executions (company_id, flow_id, flow_version);

CREATE INDEX IF NOT EXISTS idx_executions_status
    ON executions (company_id, status);

CREATE TABLE IF NOT EXISTS task_run (
    id              varchar(64) NOT NULL,
    execution_id    varchar(64) NOT NULL,
    task_id         varchar(64) NOT NULL,
    parent_id       varchar(64),
    status          varchar(16) NOT NULL,
    start_at        bigint,
    end_at          bigint,
    inputs          jsonb NOT NULL DEFAULT '{}'::jsonb,
    outputs         jsonb NOT NULL DEFAULT '{}'::jsonb,
    error           text,
    "order"         integer NOT NULL,
    created_at      bigint NOT NULL DEFAULT (
        (extract(epoch FROM clock_timestamp()) * 1000)::bigint
    ),
    updated_at      bigint NOT NULL DEFAULT (
        (extract(epoch FROM clock_timestamp()) * 1000)::bigint
    ),
    deleted_at      bigint,

    CONSTRAINT pk_task_run
        PRIMARY KEY (id),
    CONSTRAINT uq_task_run_execution_id
        UNIQUE (execution_id, id),
    CONSTRAINT uq_task_run_order
        UNIQUE (execution_id, "order"),
    CONSTRAINT ck_task_run_parent
        CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT ck_task_run_order
        CHECK ("order" >= 0),
    CONSTRAINT ck_task_run_status
        CHECK (
            status IN (
                'CREATED',
                'RUNNING',
                'COMPLETED',
                'FAILED',
                'CANCELED'
            )
        ),
    CONSTRAINT ck_task_run_inputs
        CHECK (jsonb_typeof(inputs) = 'object'),
    CONSTRAINT ck_task_run_outputs
        CHECK (jsonb_typeof(outputs) = 'object'),
    CONSTRAINT ck_task_run_error
        CHECK (status <> 'FAILED' OR length(btrim(error)) > 0),
    CONSTRAINT ck_task_run_time
        CHECK (
            start_at IS NULL
            OR end_at IS NULL
            OR end_at >= start_at
        )
);

CREATE INDEX IF NOT EXISTS idx_task_run_execution_status
    ON task_run (execution_id, status);

CREATE INDEX IF NOT EXISTS idx_task_run_task
    ON task_run (task_id);

CREATE INDEX IF NOT EXISTS idx_task_run_parent
    ON task_run (execution_id, parent_id, "order");

CREATE TABLE IF NOT EXISTS assignment (
    company_id       varchar(64) NOT NULL,
    id               varchar(64) NOT NULL,
    execution_id     varchar(64) NOT NULL,
    task_run_id      varchar(64) NOT NULL,
    allowed_outputs  jsonb NOT NULL DEFAULT '[]'::jsonb,
    status           varchar(16) NOT NULL,
    outputs          jsonb NOT NULL DEFAULT '{}'::jsonb,
    lock_version     bigint NOT NULL DEFAULT 0,
    created_at       bigint NOT NULL DEFAULT (
        (extract(epoch FROM clock_timestamp()) * 1000)::bigint
    ),
    updated_at       bigint NOT NULL DEFAULT (
        (extract(epoch FROM clock_timestamp()) * 1000)::bigint
    ),

    CONSTRAINT pk_assignment
        PRIMARY KEY (company_id, id),
    CONSTRAINT uq_assignment_task_run
        UNIQUE (company_id, task_run_id),
    CONSTRAINT ck_assignment_allowed_outputs
        CHECK (jsonb_typeof(allowed_outputs) = 'array'),
    CONSTRAINT ck_assignment_status
        CHECK (status IN ('WAITING', 'COMPLETED', 'CANCELED')),
    CONSTRAINT ck_assignment_outputs
        CHECK (jsonb_typeof(outputs) = 'object'),
    CONSTRAINT ck_assignment_lock_version
        CHECK (lock_version >= 0)
);

CREATE INDEX IF NOT EXISTS idx_assignment_waiting
    ON assignment (company_id, status, created_at)
    WHERE status = 'WAITING';

CREATE INDEX IF NOT EXISTS idx_assignment_execution
    ON assignment (company_id, execution_id);
