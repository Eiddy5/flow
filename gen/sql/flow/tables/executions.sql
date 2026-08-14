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
    inputs           jsonb NOT NULL DEFAULT '{}'::jsonb,

    CONSTRAINT pk_executions
        PRIMARY KEY (company_id, id),
    CONSTRAINT uq_executions_id
        UNIQUE (id),
    CONSTRAINT uq_executions_flow
        UNIQUE (company_id, id, flow_id, flow_reversion),
    CONSTRAINT ck_executions_reversion
        CHECK (flow_reversion > 0),
    CONSTRAINT ck_executions_inputs
        CHECK (jsonb_typeof(inputs) = 'object'),
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
