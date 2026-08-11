CREATE TABLE IF NOT EXISTS task_runs (
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

    CONSTRAINT pk_task_runs
        PRIMARY KEY (id),
    CONSTRAINT uq_task_runs_execution_id
        UNIQUE (execution_id, id),
    CONSTRAINT uq_task_runs_order
        UNIQUE (execution_id, "order"),
    CONSTRAINT ck_task_runs_parent
        CHECK (parent_id IS NULL OR parent_id <> id),
    CONSTRAINT ck_task_runs_iteration
        CHECK (
            iteration IS NULL
            OR (iteration > 0 AND parent_id IS NOT NULL)
        ),
    CONSTRAINT ck_task_runs_order
        CHECK ("order" >= 0),
    CONSTRAINT ck_task_runs_state
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
    CONSTRAINT ck_task_runs_inputs
        CHECK (jsonb_typeof(inputs) = 'object'),
    CONSTRAINT ck_task_runs_outputs
        CHECK (jsonb_typeof(outputs) = 'object'),
    CONSTRAINT ck_task_runs_error
        CHECK (
            error IS NULL
            OR (
                state ->> 'current' = 'FAILED'
                AND length(btrim(error)) > 0
            )
        ),
    CONSTRAINT ck_task_runs_time
        CHECK (
            start_at IS NULL
            OR end_at IS NULL
            OR end_at >= start_at
        )
);

CREATE INDEX IF NOT EXISTS idx_task_runs_execution_state_current
    ON task_runs (execution_id, (state ->> 'current'));

CREATE INDEX IF NOT EXISTS idx_task_runs_task
    ON task_runs (task_id);

CREATE UNIQUE INDEX IF NOT EXISTS uq_task_runs_occurrence
    ON task_runs (
        execution_id,
        task_id,
        COALESCE(parent_id, ''),
        COALESCE(iteration, 0)
    );

CREATE INDEX IF NOT EXISTS idx_task_runs_parent
    ON task_runs (execution_id, parent_id, iteration, "order");
