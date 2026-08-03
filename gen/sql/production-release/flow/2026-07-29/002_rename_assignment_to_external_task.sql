DO $$
BEGIN
    IF to_regclass('public.assignment') IS NOT NULL
        AND to_regclass('public.external_task') IS NOT NULL THEN
        RAISE EXCEPTION
            'Both assignment and external_task tables exist';
    END IF;

    IF to_regclass('public.external_task') IS NULL THEN
        IF to_regclass('public.assignment') IS NULL THEN
            RAISE EXCEPTION
                'Neither assignment nor external_task table exists';
        END IF;
        ALTER TABLE public.assignment RENAME TO external_task;
    END IF;
END
$$;

ALTER TABLE external_task
    DROP CONSTRAINT IF EXISTS ck_assignment_allowed_outputs,
    DROP COLUMN IF EXISTS allowed_outputs;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'pk_assignment'
          AND conrelid = 'external_task'::regclass
    ) THEN
        ALTER TABLE external_task
            RENAME CONSTRAINT pk_assignment TO pk_external_task;
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'uq_assignment_task_run'
          AND conrelid = 'external_task'::regclass
    ) THEN
        ALTER TABLE external_task
            RENAME CONSTRAINT uq_assignment_task_run
            TO uq_external_task_task_run;
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_assignment_status'
          AND conrelid = 'external_task'::regclass
    ) THEN
        ALTER TABLE external_task
            RENAME CONSTRAINT ck_assignment_status
            TO ck_external_task_status;
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_assignment_outputs'
          AND conrelid = 'external_task'::regclass
    ) THEN
        ALTER TABLE external_task
            RENAME CONSTRAINT ck_assignment_outputs
            TO ck_external_task_outputs;
    END IF;
    IF EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'ck_assignment_lock_version'
          AND conrelid = 'external_task'::regclass
    ) THEN
        ALTER TABLE external_task
            RENAME CONSTRAINT ck_assignment_lock_version
            TO ck_external_task_lock_version;
    END IF;
END
$$;

ALTER INDEX IF EXISTS idx_assignment_waiting
    RENAME TO idx_external_task_waiting;
ALTER INDEX IF EXISTS idx_assignment_execution
    RENAME TO idx_external_task_execution;
