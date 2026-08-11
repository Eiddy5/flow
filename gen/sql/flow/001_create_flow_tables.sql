-- Flow is still in development. This file is the complete schema baseline.
-- Change it in place and recreate existing development databases; it does not
-- provide an upgrade path for data created by an older baseline.

CREATE TABLE IF NOT EXISTS flow_drafts (
    id               varchar(64) NOT NULL,
    company_id       varchar(64) NOT NULL,

    creator          jsonb NOT NULL,
    creator_id       varchar(64)
        GENERATED ALWAYS AS (creator ->> 'id') STORED,
    updater          jsonb NOT NULL,
    updater_id       varchar(64)
        GENERATED ALWAYS AS (updater ->> 'id') STORED,
    deleter          jsonb,
    deleter_id       varchar(64)
        GENERATED ALWAYS AS (deleter ->> 'id') STORED,

    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    deleted_at       timestamptz,

    raw              text NOT NULL,
    lock_version     bigint NOT NULL DEFAULT 0,
    deleted          boolean NOT NULL DEFAULT false,

    CONSTRAINT pk_flow_drafts
        PRIMARY KEY (company_id, id),
    CONSTRAINT ck_flow_drafts_id
        CHECK (length(btrim(id)) > 0),
    CONSTRAINT ck_flow_drafts_lock_version
        CHECK (lock_version >= 0),
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
            (
                deleted IS FALSE
                AND deleter IS NULL
                AND deleted_at IS NULL
            )
            OR (
                deleted IS TRUE
                AND deleter IS NOT NULL
                AND deleted_at IS NOT NULL
            )
        )
);

CREATE INDEX IF NOT EXISTS idx_flow_drafts_current
    ON flow_drafts (company_id, id)
    WHERE deleted IS FALSE;

CREATE INDEX IF NOT EXISTS idx_flow_drafts_creator_id
    ON flow_drafts (creator_id);

CREATE INDEX IF NOT EXISTS idx_flow_drafts_updater_id
    ON flow_drafts (updater_id);

CREATE INDEX IF NOT EXISTS idx_flow_drafts_deleter_id
    ON flow_drafts (deleter_id)
    WHERE deleter_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS flows (
    id             varchar(64) NOT NULL,
    key            varchar(128) NOT NULL,
    company_id     varchar(64) NOT NULL,
    reversion      bigint NOT NULL,
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

    created_at     timestamptz NOT NULL DEFAULT now(),
    updated_at     timestamptz NOT NULL DEFAULT now(),
    deleted_at     timestamptz,

    inputs         jsonb NOT NULL DEFAULT '[]'::jsonb,
    outputs        jsonb NOT NULL DEFAULT '[]'::jsonb,
    deleted        boolean NOT NULL DEFAULT false,

    CONSTRAINT pk_flows
        PRIMARY KEY (company_id, id, reversion),
    CONSTRAINT uq_flows_company_key_reversion
        UNIQUE (company_id, key, reversion),
    CONSTRAINT ck_flows_id
        CHECK (length(btrim(id)) > 0),
    CONSTRAINT ck_flows_key
        CHECK (length(btrim(key)) BETWEEN 1 AND 128),
    CONSTRAINT ck_flows_reversion
        CHECK (reversion > 0),
    CONSTRAINT ck_flows_inputs
        CHECK (jsonb_typeof(inputs) = 'array'),
    CONSTRAINT ck_flows_outputs
        CHECK (jsonb_typeof(outputs) = 'array'),
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
            (
                deleted IS FALSE
                AND deleter IS NULL
                AND deleted_at IS NULL
            )
            OR (
                deleted IS TRUE
                AND deleter IS NOT NULL
                AND deleted_at IS NOT NULL
            )
        )
);

CREATE INDEX IF NOT EXISTS idx_flows_latest
    ON flows (company_id, id, reversion DESC);

CREATE INDEX IF NOT EXISTS idx_flows_creator_id
    ON flows (creator_id);

CREATE INDEX IF NOT EXISTS idx_flows_updater_id
    ON flows (updater_id);

CREATE INDEX IF NOT EXISTS idx_flows_deleter_id
    ON flows (deleter_id)
    WHERE deleter_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS flow_tasks (
    company_id       varchar(64) NOT NULL,
    id               varchar(64) NOT NULL,
    type             text NOT NULL,
    route            text NOT NULL DEFAULT 'DIRECT',
    inputs           jsonb NOT NULL DEFAULT '[]'::jsonb,
    outputs          jsonb NOT NULL DEFAULT '[]'::jsonb,
    properties       jsonb NOT NULL DEFAULT '{}'::jsonb,
    flow_id          varchar(64) NOT NULL,
    flow_reversion   bigint NOT NULL,
    parent_id        varchar(64),
    "order"          integer NOT NULL,
    key              varchar(128) NOT NULL,
    depend_on        jsonb NOT NULL DEFAULT '[]'::jsonb,

    CONSTRAINT pk_flow_tasks
        PRIMARY KEY (company_id, flow_id, flow_reversion, id),
    CONSTRAINT uq_flow_tasks_key
        UNIQUE (company_id, flow_id, flow_reversion, key),
    CONSTRAINT ck_flow_tasks_parent
        CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT ck_flow_tasks_order
        CHECK ("order" >= 0),
    CONSTRAINT ck_flow_tasks_key
        CHECK (length(btrim(key)) BETWEEN 1 AND 128),
    CONSTRAINT ck_flow_tasks_type
        CHECK (
            type <> ''
            AND type = btrim(type)
        ),
    CONSTRAINT ck_flow_tasks_route
        CHECK (length(btrim(route)) > 0),
    CONSTRAINT ck_flow_tasks_inputs
        CHECK (jsonb_typeof(inputs) = 'array'),
    CONSTRAINT ck_flow_tasks_outputs
        CHECK (jsonb_typeof(outputs) = 'array'),
    CONSTRAINT ck_flow_tasks_properties
        CHECK (jsonb_typeof(properties) = 'object'),
    CONSTRAINT ck_flow_tasks_depend_on
        CHECK (jsonb_typeof(depend_on) = 'array'),
    CONSTRAINT ck_flow_tasks_reversion
        CHECK (flow_reversion > 0)
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_flow_tasks_order
    ON flow_tasks (
        company_id,
        flow_id,
        flow_reversion,
        COALESCE(parent_id, ''),
        "order"
    );

CREATE INDEX IF NOT EXISTS idx_flow_tasks_tree
    ON flow_tasks (
        company_id,
        flow_id,
        flow_reversion,
        parent_id,
        "order"
    );

CREATE INDEX IF NOT EXISTS idx_flow_tasks_type
    ON flow_tasks (company_id, type);

CREATE TABLE IF NOT EXISTS executions (
    id               varchar(64) NOT NULL,
    company_id       varchar(64) NOT NULL,
    flow_id          varchar(64) NOT NULL,
    flow_reversion   bigint NOT NULL,
    state            jsonb NOT NULL,
    lock_version     bigint NOT NULL DEFAULT 0,

    creator          jsonb NOT NULL,
    updater          jsonb NOT NULL,
    deleter          jsonb,

    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    deleted_at       timestamptz,

    CONSTRAINT pk_executions
        PRIMARY KEY (company_id, id),
    CONSTRAINT uq_executions_id
        UNIQUE (id),
    CONSTRAINT uq_executions_flow
        UNIQUE (company_id, id, flow_id, flow_reversion),
    CONSTRAINT ck_executions_reversion
        CHECK (flow_reversion > 0),
    CONSTRAINT ck_executions_state
        CHECK (
            jsonb_typeof(state) = 'object'
            AND state ? 'current'
            AND state ? 'history'
            AND jsonb_typeof(state -> 'current') = 'string'
            AND state ->> 'current' IN (
                'CREATED',
                'RUNNING',
                'PAUSED',
                'RESTARTED',
                'SUCCESS',
                'WARNING',
                'FAILED',
                'KILLING',
                'KILLED'
            )
            AND jsonb_typeof(state -> 'history') = 'array'
            AND jsonb_array_length(state -> 'history') > 0
            AND state -> 'history' -> 0 ->> 'state' = 'CREATED'
            AND state -> 'history' -> -1 ->> 'state'
                = state ->> 'current'
            AND jsonb_typeof(
                state -> 'history' -> 0 -> 'date'
            ) = 'number'
            AND jsonb_typeof(
                state -> 'history' -> -1 -> 'date'
            ) = 'number'
            AND jsonb_array_length(
                jsonb_path_query_array(
                    state -> 'history',
                    '$[*] ? (
                        @.state.type() == "string"
                        && @.date.type() == "number"
                    )'
                )
            ) = jsonb_array_length(state -> 'history')
            AND NOT jsonb_path_exists(
                state -> 'history',
                '$[*] ? (
                    @.state != "CREATED"
                    && @.state != "RUNNING"
                    && @.state != "PAUSED"
                    && @.state != "RESTARTED"
                    && @.state != "SUCCESS"
                    && @.state != "WARNING"
                    && @.state != "FAILED"
                    && @.state != "KILLING"
                    && @.state != "KILLED"
                )'
            )
            AND NOT jsonb_path_exists(
                state -> 'history',
                '$[*] ? (@.date < 0)'
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
    ON executions (company_id, flow_id, flow_reversion);

CREATE INDEX IF NOT EXISTS idx_executions_state_current
    ON executions (company_id, (state ->> 'current'));

CREATE TABLE IF NOT EXISTS task_run (
    id               varchar(64) NOT NULL,
    execution_id     varchar(64) NOT NULL,
    task_id          varchar(64) NOT NULL,
    parent_id        varchar(64),
    iteration        integer,
    state            jsonb NOT NULL,
    start_at         timestamptz,
    end_at           timestamptz,
    inputs           jsonb NOT NULL DEFAULT '{}'::jsonb,
    outputs          jsonb NOT NULL DEFAULT '{}'::jsonb,
    error            text,
    "order"          integer NOT NULL,
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    deleted_at       timestamptz,

    CONSTRAINT pk_task_run
        PRIMARY KEY (id),
    CONSTRAINT uq_task_run_execution_id
        UNIQUE (execution_id, id),
    CONSTRAINT uq_task_run_order
        UNIQUE (execution_id, "order"),
    CONSTRAINT ck_task_run_parent
        CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT ck_task_run_iteration
        CHECK (
            iteration IS NULL
            OR (iteration > 0 AND parent_id IS NOT NULL)
        ),
    CONSTRAINT ck_task_run_order
        CHECK ("order" >= 0),
    CONSTRAINT ck_task_run_state
        CHECK (
            jsonb_typeof(state) = 'object'
            AND state ? 'current'
            AND state ? 'history'
            AND jsonb_typeof(state -> 'current') = 'string'
            AND state ->> 'current' IN (
                'CREATED',
                'RUNNING',
                'PAUSED',
                'SUCCESS',
                'WARNING',
                'FAILED',
                'KILLED'
            )
            AND jsonb_typeof(state -> 'history') = 'array'
            AND jsonb_array_length(state -> 'history') > 0
            AND state -> 'history' -> 0 ->> 'state' = 'CREATED'
            AND state -> 'history' -> -1 ->> 'state'
                = state ->> 'current'
            AND jsonb_typeof(
                state -> 'history' -> 0 -> 'date'
            ) = 'number'
            AND jsonb_typeof(
                state -> 'history' -> -1 -> 'date'
            ) = 'number'
            AND jsonb_array_length(
                jsonb_path_query_array(
                    state -> 'history',
                    '$[*] ? (
                        @.state.type() == "string"
                        && @.date.type() == "number"
                    )'
                )
            ) = jsonb_array_length(state -> 'history')
            AND NOT jsonb_path_exists(
                state -> 'history',
                '$[*] ? (
                    @.state != "CREATED"
                    && @.state != "RUNNING"
                    && @.state != "PAUSED"
                    && @.state != "SUCCESS"
                    && @.state != "WARNING"
                    && @.state != "FAILED"
                    && @.state != "KILLED"
                )'
            )
            AND NOT jsonb_path_exists(
                state -> 'history',
                '$[*] ? (@.date < 0)'
            )
        ),
    CONSTRAINT ck_task_run_inputs
        CHECK (jsonb_typeof(inputs) = 'object'),
    CONSTRAINT ck_task_run_outputs
        CHECK (jsonb_typeof(outputs) = 'object'),
    CONSTRAINT ck_task_run_error
        CHECK (
            error IS NULL
            OR (
                state ->> 'current' = 'FAILED'
                AND length(btrim(error)) > 0
            )
        ),
    CONSTRAINT ck_task_run_time
        CHECK (
            start_at IS NULL
            OR end_at IS NULL
            OR end_at >= start_at
        )
);

CREATE INDEX IF NOT EXISTS idx_task_run_execution_state_current
    ON task_run (execution_id, (state ->> 'current'));

CREATE INDEX IF NOT EXISTS idx_task_run_task
    ON task_run (task_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_task_run_occurrence
    ON task_run (
        execution_id,
        task_id,
        COALESCE(parent_id, ''),
        COALESCE(iteration, 0)
    );

CREATE INDEX IF NOT EXISTS idx_task_run_parent
    ON task_run (execution_id, parent_id, iteration, "order");

CREATE TABLE IF NOT EXISTS external_task (
    company_id      varchar(64) NOT NULL,
    id              varchar(64) NOT NULL,
    execution_id    varchar(64) NOT NULL,
    task_run_id     varchar(64) NOT NULL,
    status          varchar(16) NOT NULL,
    outputs         jsonb NOT NULL DEFAULT '{}'::jsonb,
    lock_version    bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_external_task
        PRIMARY KEY (company_id, id),
    CONSTRAINT uq_external_task_task_run
        UNIQUE (company_id, task_run_id),
    CONSTRAINT ck_external_task_status
        CHECK (status IN ('WAITING', 'COMPLETED', 'CANCELED')),
    CONSTRAINT ck_external_task_outputs
        CHECK (jsonb_typeof(outputs) = 'object'),
    CONSTRAINT ck_external_task_lock_version
        CHECK (lock_version >= 0)
);

CREATE INDEX IF NOT EXISTS idx_external_task_waiting
    ON external_task (company_id, status, created_at)
    WHERE status = 'WAITING';

CREATE INDEX IF NOT EXISTS idx_external_task_execution
    ON external_task (company_id, execution_id);

CREATE TABLE IF NOT EXISTS dispatch_queue_messages (
    id            varchar(64) NOT NULL,
    queue_name    varchar(250) NOT NULL,
    event_key     text,
    payload       jsonb NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_dispatch_queue_messages
        PRIMARY KEY (id),
    CONSTRAINT ck_dispatch_queue_messages_id
        CHECK (length(btrim(id)) > 0),
    CONSTRAINT ck_dispatch_queue_messages_payload
        CHECK (jsonb_typeof(payload) = 'object')
);

CREATE INDEX IF NOT EXISTS idx_dispatch_queue_messages_pending
    ON dispatch_queue_messages (queue_name, created_at, id);
