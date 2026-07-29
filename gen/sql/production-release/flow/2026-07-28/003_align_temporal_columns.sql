ALTER TABLE flows
    ALTER COLUMN created_at DROP DEFAULT,
    ALTER COLUMN updated_at DROP DEFAULT,
    ALTER COLUMN created_at TYPE timestamptz
        USING to_timestamp(created_at::double precision / 1000.0),
    ALTER COLUMN updated_at TYPE timestamptz
        USING to_timestamp(updated_at::double precision / 1000.0),
    ALTER COLUMN deleted_at TYPE timestamptz
        USING to_timestamp(deleted_at::double precision / 1000.0),
    ALTER COLUMN created_at SET DEFAULT now(),
    ALTER COLUMN updated_at SET DEFAULT now();

ALTER TABLE flow_drafts
    ALTER COLUMN created_at DROP DEFAULT,
    ALTER COLUMN updated_at DROP DEFAULT,
    ALTER COLUMN created_at TYPE timestamptz
        USING to_timestamp(created_at::double precision / 1000.0),
    ALTER COLUMN updated_at TYPE timestamptz
        USING to_timestamp(updated_at::double precision / 1000.0),
    ALTER COLUMN deleted_at TYPE timestamptz
        USING to_timestamp(deleted_at::double precision / 1000.0),
    ALTER COLUMN created_at SET DEFAULT now(),
    ALTER COLUMN updated_at SET DEFAULT now();

ALTER TABLE executions
    ALTER COLUMN created_at DROP DEFAULT,
    ALTER COLUMN updated_at DROP DEFAULT,
    ALTER COLUMN created_at TYPE timestamptz
        USING to_timestamp(created_at::double precision / 1000.0),
    ALTER COLUMN updated_at TYPE timestamptz
        USING to_timestamp(updated_at::double precision / 1000.0),
    ALTER COLUMN deleted_at TYPE timestamptz
        USING to_timestamp(deleted_at::double precision / 1000.0),
    ALTER COLUMN created_at SET DEFAULT now(),
    ALTER COLUMN updated_at SET DEFAULT now();

ALTER TABLE task_run
    DROP CONSTRAINT IF EXISTS ck_task_run_time,
    ALTER COLUMN created_at DROP DEFAULT,
    ALTER COLUMN updated_at DROP DEFAULT,
    ALTER COLUMN start_at TYPE timestamptz
        USING to_timestamp(start_at::double precision / 1000.0),
    ALTER COLUMN end_at TYPE timestamptz
        USING to_timestamp(end_at::double precision / 1000.0),
    ALTER COLUMN created_at TYPE timestamptz
        USING to_timestamp(created_at::double precision / 1000.0),
    ALTER COLUMN updated_at TYPE timestamptz
        USING to_timestamp(updated_at::double precision / 1000.0),
    ALTER COLUMN deleted_at TYPE timestamptz
        USING to_timestamp(deleted_at::double precision / 1000.0),
    ALTER COLUMN created_at SET DEFAULT now(),
    ALTER COLUMN updated_at SET DEFAULT now(),
    ADD CONSTRAINT ck_task_run_time
        CHECK (
            start_at IS NULL
            OR end_at IS NULL
            OR end_at >= start_at
        );

ALTER TABLE assignment
    ALTER COLUMN created_at DROP DEFAULT,
    ALTER COLUMN updated_at DROP DEFAULT,
    ALTER COLUMN created_at TYPE timestamptz
        USING to_timestamp(created_at::double precision / 1000.0),
    ALTER COLUMN updated_at TYPE timestamptz
        USING to_timestamp(updated_at::double precision / 1000.0),
    ALTER COLUMN created_at SET DEFAULT now(),
    ALTER COLUMN updated_at SET DEFAULT now();
