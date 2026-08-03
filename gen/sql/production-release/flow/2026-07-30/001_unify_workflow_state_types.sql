BEGIN;

ALTER TABLE executions
    DROP CONSTRAINT IF EXISTS ck_executions_status,
    DROP CONSTRAINT IF EXISTS ck_executions_state_history;

ALTER TABLE task_run
    DROP CONSTRAINT IF EXISTS ck_task_run_status,
    DROP CONSTRAINT IF EXISTS ck_task_run_error,
    DROP CONSTRAINT IF EXISTS ck_task_run_state_history;

UPDATE task_run
SET status = CASE status
    WHEN 'ACTIVE' THEN 'RUNNING'
    WHEN 'FAILED' THEN 'TERMINATED'
    WHEN 'CANCELED' THEN 'TERMINATED'
    ELSE status
END
WHERE status IN ('ACTIVE', 'FAILED', 'CANCELED');

DO $$
BEGIN
    IF to_regclass('public.external_task') IS NOT NULL THEN
        UPDATE task_run AS task_run_to_wait
        SET status = 'WAITING'
        FROM external_task
        WHERE external_task.execution_id = task_run_to_wait.execution_id
          AND external_task.task_run_id = task_run_to_wait.id
          AND external_task.status = 'WAITING'
          AND task_run_to_wait.status IN ('CREATED', 'RUNNING');
    END IF;
END
$$;

UPDATE executions
SET status = CASE status
    WHEN 'ACTIVE' THEN 'RUNNING'
    WHEN 'FAILED' THEN 'TERMINATED'
    WHEN 'CANCELED' THEN 'TERMINATED'
    ELSE status
END
WHERE status IN ('ACTIVE', 'FAILED', 'CANCELED');

UPDATE task_run
SET status = 'TERMINATED',
    error = NULL
WHERE status IN ('CREATED', 'RUNNING', 'WAITING')
  AND EXISTS (
      SELECT 1
      FROM executions
      WHERE executions.id = task_run.execution_id
        AND executions.status = 'TERMINATED'
  );

UPDATE task_run
SET error = NULL
WHERE status <> 'TERMINATED'
  AND error IS NOT NULL;

UPDATE executions
SET status = 'WAITING'
WHERE status IN ('CREATED', 'RUNNING')
  AND EXISTS (
      SELECT 1
      FROM task_run
      WHERE task_run.execution_id = executions.id
        AND task_run.status = 'WAITING'
  )
  AND NOT EXISTS (
      SELECT 1
      FROM task_run
      WHERE task_run.execution_id = executions.id
        AND task_run.status IN ('CREATED', 'RUNNING')
  );

ALTER TABLE executions
    ADD COLUMN IF NOT EXISTS state_history jsonb;

ALTER TABLE task_run
    ADD COLUMN IF NOT EXISTS state_history jsonb;

UPDATE executions
SET state_history = CASE status
    WHEN 'CREATED' THEN jsonb_build_array(
        jsonb_build_object(
            'state', 'CREATED',
            'date', (extract(epoch FROM created_at) * 1000)::bigint
        )
    )
    WHEN 'RUNNING' THEN jsonb_build_array(
        jsonb_build_object(
            'state', 'CREATED',
            'date', (extract(epoch FROM created_at) * 1000)::bigint
        ),
        jsonb_build_object(
            'state', 'RUNNING',
            'date', (extract(epoch FROM created_at) * 1000)::bigint
        )
    )
    WHEN 'WAITING' THEN jsonb_build_array(
        jsonb_build_object(
            'state', 'CREATED',
            'date', (extract(epoch FROM created_at) * 1000)::bigint
        ),
        jsonb_build_object(
            'state', 'RUNNING',
            'date', (extract(epoch FROM created_at) * 1000)::bigint
        ),
        jsonb_build_object(
            'state', 'WAITING',
            'date', (extract(epoch FROM updated_at) * 1000)::bigint
        )
    )
    WHEN 'COMPLETED' THEN jsonb_build_array(
        jsonb_build_object(
            'state', 'CREATED',
            'date', (extract(epoch FROM created_at) * 1000)::bigint
        ),
        jsonb_build_object(
            'state', 'RUNNING',
            'date', (extract(epoch FROM created_at) * 1000)::bigint
        ),
        jsonb_build_object(
            'state', 'COMPLETED',
            'date', (extract(epoch FROM updated_at) * 1000)::bigint
        )
    )
    WHEN 'TERMINATED' THEN jsonb_build_array(
        jsonb_build_object(
            'state', 'CREATED',
            'date', (extract(epoch FROM created_at) * 1000)::bigint
        ),
        jsonb_build_object(
            'state', 'RUNNING',
            'date', (extract(epoch FROM created_at) * 1000)::bigint
        ),
        jsonb_build_object(
            'state', 'TERMINATED',
            'date', (extract(epoch FROM updated_at) * 1000)::bigint
        )
    )
END
WHERE state_history IS NULL;

UPDATE task_run
SET state_history = CASE status
    WHEN 'CREATED' THEN jsonb_build_array(
        jsonb_build_object(
            'state', 'CREATED',
            'date', (extract(epoch FROM created_at) * 1000)::bigint
        )
    )
    WHEN 'RUNNING' THEN jsonb_build_array(
        jsonb_build_object(
            'state', 'CREATED',
            'date', (extract(epoch FROM created_at) * 1000)::bigint
        ),
        jsonb_build_object(
            'state', 'RUNNING',
            'date', (extract(epoch FROM created_at) * 1000)::bigint
        )
    )
    WHEN 'WAITING' THEN jsonb_build_array(
        jsonb_build_object(
            'state', 'CREATED',
            'date', (extract(epoch FROM created_at) * 1000)::bigint
        ),
        jsonb_build_object(
            'state', 'RUNNING',
            'date', (extract(epoch FROM created_at) * 1000)::bigint
        ),
        jsonb_build_object(
            'state', 'WAITING',
            'date', (extract(epoch FROM updated_at) * 1000)::bigint
        )
    )
    WHEN 'COMPLETED' THEN jsonb_build_array(
        jsonb_build_object(
            'state', 'CREATED',
            'date', (extract(epoch FROM created_at) * 1000)::bigint
        ),
        jsonb_build_object(
            'state', 'RUNNING',
            'date', (extract(epoch FROM created_at) * 1000)::bigint
        ),
        jsonb_build_object(
            'state', 'COMPLETED',
            'date', (extract(epoch FROM updated_at) * 1000)::bigint
        )
    )
    WHEN 'TERMINATED' THEN jsonb_build_array(
        jsonb_build_object(
            'state', 'CREATED',
            'date', (extract(epoch FROM created_at) * 1000)::bigint
        ),
        jsonb_build_object(
            'state', 'RUNNING',
            'date', (extract(epoch FROM created_at) * 1000)::bigint
        ),
        jsonb_build_object(
            'state', 'TERMINATED',
            'date', (extract(epoch FROM updated_at) * 1000)::bigint
        )
    )
END
WHERE state_history IS NULL;

ALTER TABLE executions
    ALTER COLUMN state_history SET NOT NULL,
    ADD CONSTRAINT ck_executions_status
        CHECK (
            status IN (
                'CREATED',
                'RUNNING',
                'WAITING',
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
                    && @.state != "WAITING"
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
    ALTER COLUMN state_history SET NOT NULL,
    ADD CONSTRAINT ck_task_run_status
        CHECK (
            status IN (
                'CREATED',
                'RUNNING',
                'WAITING',
                'COMPLETED',
                'TERMINATED'
            )
        ),
    ADD CONSTRAINT ck_task_run_error
        CHECK (
            error IS NULL
            OR (
                status = 'TERMINATED'
                AND length(btrim(error)) > 0
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
                    && @.state != "WAITING"
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
