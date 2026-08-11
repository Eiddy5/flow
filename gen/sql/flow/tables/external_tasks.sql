CREATE TABLE IF NOT EXISTS external_tasks (
    company_id      varchar(64) NOT NULL,
    id              varchar(64) NOT NULL,
    execution_id    varchar(64) NOT NULL,
    task_run_id     varchar(64) NOT NULL,
    status          varchar(16) NOT NULL,
    outputs         jsonb NOT NULL DEFAULT '{}'::jsonb,
    lock_version    bigint NOT NULL DEFAULT 0,
    created_at      timestamptz NOT NULL DEFAULT now(),
    updated_at      timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT pk_external_tasks
        PRIMARY KEY (company_id, id),
    CONSTRAINT uq_external_tasks_task_run
        UNIQUE (company_id, task_run_id),
    CONSTRAINT ck_external_tasks_status
        CHECK (status IN ('WAITING', 'COMPLETED', 'CANCELED')),
    CONSTRAINT ck_external_tasks_outputs
        CHECK (jsonb_typeof(outputs) = 'object'),
    CONSTRAINT ck_external_tasks_lock_version
        CHECK (lock_version >= 0)
);

CREATE INDEX IF NOT EXISTS idx_external_tasks_waiting
    ON external_tasks (company_id, status, created_at)
    WHERE status = 'WAITING';

CREATE INDEX IF NOT EXISTS idx_external_tasks_execution
    ON external_tasks (company_id, execution_id);
