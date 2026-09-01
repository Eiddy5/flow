CREATE TABLE IF NOT EXISTS task_runs (
    id               varchar(64) NOT NULL,
    execution_id     varchar(64) NOT NULL,
    task_id          varchar(64) NOT NULL,
    parent_id        varchar(64),
    iteration        integer,
    execution_generation_version integer,
    generation       jsonb NOT NULL DEFAULT '{"current": null, "history": []}'::jsonb,
    state            jsonb NOT NULL,
    inputs           jsonb NOT NULL DEFAULT '{}'::jsonb,
    outputs          jsonb NOT NULL DEFAULT '{}'::jsonb,
    error            text,
    "order"          integer NOT NULL,

    CONSTRAINT pk_task_runs
        PRIMARY KEY (id),
    CONSTRAINT uq_task_runs_execution_id
        UNIQUE (execution_id, id),
    CONSTRAINT uq_task_runs_order
        UNIQUE (execution_id, "order")
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
        COALESCE(iteration, 0),
        COALESCE(execution_generation_version, 0)
    );

CREATE INDEX IF NOT EXISTS idx_task_runs_parent
    ON task_runs (execution_id, parent_id, iteration, "order");
