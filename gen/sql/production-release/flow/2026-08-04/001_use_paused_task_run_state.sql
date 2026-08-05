BEGIN;

-- ADR 0029 explicitly chooses a breaking runtime-state migration. Existing
-- Execution/TaskRun facts are not compatible with the new PAUSED ownership
-- rule and are intentionally discarded rather than guessed or backfilled.
-- These tables deliberately have no foreign keys, so list every runtime table
-- instead of relying on CASCADE to discover application-owned relationships.
TRUNCATE TABLE external_task, task_run, executions;

ALTER TABLE executions
    DROP CONSTRAINT IF EXISTS ck_executions_status,
    DROP CONSTRAINT IF EXISTS ck_executions_state_history;

ALTER TABLE executions
    ADD CONSTRAINT ck_executions_status
        CHECK (
            status IN (
                'CREATED',
                'RUNNING',
                'COMPLETED',
                'TERMINATED'
            )
        ),
    ADD CONSTRAINT ck_executions_state_history
        CHECK (
            jsonb_typeof(state_history) = 'array'
            AND jsonb_array_length(state_history) > 0
            AND state_history -> 0 ->> 'state' = 'CREATED'
            AND state_history -> -1 ->> 'state' = status
            AND jsonb_typeof(state_history -> 0 -> 'date') = 'number'
            AND jsonb_typeof(state_history -> -1 -> 'date') = 'number'
            AND jsonb_array_length(
                jsonb_path_query_array(
                    state_history,
                    '$[*] ? (
                        @.state.type() == "string"
                        && @.date.type() == "number"
                    )'
                )
            ) = jsonb_array_length(state_history)
            AND NOT jsonb_path_exists(
                state_history,
                '$[*] ? (
                    @.state != "CREATED"
                    && @.state != "RUNNING"
                    && @.state != "COMPLETED"
                    && @.state != "TERMINATED"
                )'
            )
            AND NOT jsonb_path_exists(
                state_history,
                '$[*] ? (@.date < 0)'
            )
        );

ALTER TABLE task_run
    DROP CONSTRAINT IF EXISTS ck_task_run_status,
    DROP CONSTRAINT IF EXISTS ck_task_run_state_history;

ALTER TABLE task_run
    ADD CONSTRAINT ck_task_run_status
        CHECK (
            status IN (
                'CREATED',
                'RUNNING',
                'PAUSED',
                'COMPLETED',
                'TERMINATED'
            )
        ),
    ADD CONSTRAINT ck_task_run_state_history
        CHECK (
            jsonb_typeof(state_history) = 'array'
            AND jsonb_array_length(state_history) > 0
            AND state_history -> 0 ->> 'state' = 'CREATED'
            AND state_history -> -1 ->> 'state' = status
            AND jsonb_typeof(state_history -> 0 -> 'date') = 'number'
            AND jsonb_typeof(state_history -> -1 -> 'date') = 'number'
            AND jsonb_array_length(
                jsonb_path_query_array(
                    state_history,
                    '$[*] ? (
                        @.state.type() == "string"
                        && @.date.type() == "number"
                    )'
                )
            ) = jsonb_array_length(state_history)
            AND NOT jsonb_path_exists(
                state_history,
                '$[*] ? (
                    @.state != "CREATED"
                    && @.state != "RUNNING"
                    && @.state != "PAUSED"
                    && @.state != "COMPLETED"
                    && @.state != "TERMINATED"
                )'
            )
            AND NOT jsonb_path_exists(
                state_history,
                '$[*] ? (@.date < 0)'
            )
        );

COMMIT;
